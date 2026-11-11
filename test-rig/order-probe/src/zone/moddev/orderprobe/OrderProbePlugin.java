package zone.moddev.orderprobe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class OrderProbePlugin implements IMixinConfigPlugin {
    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Override public void onLoad(String mixinPackage) {
        try {
            byte[] raw;
            try (InputStream stream = getClass().getResourceAsStream("/order-probe-pins.json")) {
                ProbeGuard.require(stream != null, "missing sealed sidecar pins");
                raw = stream.readAllBytes();
            }
            JsonObject pins = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            ProbeGuard.require(MixinEnvironment.getCurrentEnvironment().getVersion().equals("0.8.7"), "native Mixin version drift");
            for (var pin : pins.getAsJsonObject("mixinClasses").entrySet()) {
                try (InputStream stream = MixinEnvironment.class.getResourceAsStream("/" + pin.getKey().replace('.', '/') + ".class")) {
                    ProbeGuard.require(stream != null && ProbeGuard.sha(stream.readAllBytes()).equals(pin.getValue().getAsString()),
                            "native Mixin implementation drift: " + pin.getKey());
                }
            }
            Path runtime = Path.of("").toRealPath();
            ProbeGuard.verifyProduction(pins, runtime.resolve("mods"));
            Class<?> wrapper = Class.forName("org.spongepowered.asm.mixin.transformer.Config", false, MixinEnvironment.class.getClassLoader());
            Class<?> configType = Class.forName("org.spongepowered.asm.mixin.transformer.MixinConfig", false, wrapper.getClassLoader());
            @SuppressWarnings("unchecked") Map<String, Object> configs = (Map<String, Object>) field(wrapper, "allConfigs").get(null);
            Map<String, Object> actual = new LinkedHashMap<>();
            for (var config : configs.entrySet()) actual.put(config.getKey(), wrapper.getMethod("getConfig").invoke(config.getValue()));
            @SuppressWarnings("unchecked") Set<String> preparedClasses = (Set<String>) field(configType, "globalMixinList").get(null);
            for (var row : pins.getAsJsonObject("configs").entrySet()) {
                for (String expectedClass : row.getValue().getAsJsonObject().getAsJsonObject("classes").keySet()) {
                    ProbeGuard.require(!preparedClasses.contains(expectedClass), "child mixin already prepared elsewhere");
                    int declarations = 0;
                    for (Object config : actual.values()) {
                        String prefix = (String) field(configType, "mixinPackage").get(config);
                        for (String collection : List.of("mixinClasses", "mixinClassesClient", "mixinClassesServer")) {
                            Object list = field(configType, collection).get(config);
                            if (list == null) continue;
                            for (Object shortName : (List<?>) list) {
                                String fullName = prefix + (prefix.endsWith(".") ? "" : ".") + shortName;
                                if (expectedClass.equals(fullName)) declarations++;
                            }
                        }
                    }
                    ProbeGuard.require(declarations == 1, "missing/duplicate production mixin declaration: " + expectedClass);
                }
            }
            Map<String, Integer> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
            Field priority = field(configType, "mixinPriority");
            for (var config : actual.entrySet()) before.put(config.getKey(), priority.getInt(config.getValue()));
            for (var row : pins.getAsJsonObject("configs").entrySet()) {
                JsonObject pin = row.getValue().getAsJsonObject();
                Object config = actual.get(row.getKey());
                ProbeGuard.require(config != null && config.getClass() == configType, "missing/duplicate child config " + row.getKey());
                ProbeGuard.require(!field(configType, "prepared").getBoolean(config), "child config already prepared");
                ProbeGuard.require(priority.getInt(config) == 1000, "unexpected native default mixin priority");
                String packageName = pin.get("package").getAsString();
                Object observedPackage = field(configType, "mixinPackage").get(config);
                ProbeGuard.require(observedPackage.equals(packageName) || observedPackage.equals(packageName + "."), "config package differs");
                ProbeGuard.require(field(configType, "mixinClasses").get(config).equals(ProbeGuard.strings(pin.getAsJsonArray("mixins"))), "config mixin list differs");
                for (String name : List.of("mixinClassesClient", "mixinClassesServer")) {
                    Object value = field(configType, name).get(config);
                    ProbeGuard.require(value == null || ((List<?>) value).isEmpty(), "unexpected side-specific child mixins");
                }
            }
            JsonObject requested = pins.getAsJsonObject("priorities");
            ProbeGuard.require(requested.keySet().equals(pins.getAsJsonObject("configs").keySet()), "priority scope differs");
            String mode = pins.get("mode").getAsString();
            ProbeGuard.require(mode.equals("power-low") || mode.equals("metals-low"), "unknown order mode");
            ProbeGuard.require(requested.get("basepower.mixins.json").getAsInt() == (mode.equals("power-low") ? 900 : 1100)
                    && requested.get("basemetals.mixins.json").getAsInt() == (mode.equals("power-low") ? 1100 : 900), "unapproved priority pair");
            for (var row : requested.entrySet()) priority.setInt(actual.get(row.getKey()), row.getValue().getAsInt());
            for (var config : actual.entrySet()) after.put(config.getKey(), priority.getInt(config.getValue()));
            for (String name : before.keySet()) {
                ProbeGuard.require(after.get(name).equals(requested.has(name) ? requested.get(name).getAsInt() : before.get(name)), "unrelated priority mutation");
            }
            Map<String, Object> proof = new LinkedHashMap<>();
            proof.put("status", "INSTRUMENTED_ORDER_TEST_ONLY");
            proof.put("qualificationAllowed", false);
            proof.put("mode", pins.get("mode").getAsString());
            proof.put("runtime", runtime.toString());
            proof.put("pid", ProcessHandle.current().pid());
            proof.put("pinsSha256", ProbeGuard.sha(raw));
            proof.put("preparedBefore", false);
            proof.put("before", before);
            proof.put("after", after);
            Files.writeString(runtime.resolve("native61-order-plugin-" + ProcessHandle.current().pid() + ".json"),
                    new Gson().toJson(proof) + "\n", StandardOpenOption.CREATE_NEW);
            System.out.println("NATIVE61_ORDER_PROBE_PRIORITIES " + pins.get("mode").getAsString());
        } catch (Throwable failure) {
            throw new org.spongepowered.asm.launch.MixinInitialisationError("ORDER_PROBE guard failed", failure);
        }
    }

    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String target, String mixin) { throw new AssertionError("Order sidecar must declare no target mixins"); }
    @Override public void acceptTargets(Set<String> own, Set<String> other) { ProbeGuard.require(own.isEmpty(), "sidecar unexpectedly targets game classes"); }
    @Override public List<String> getMixins() { return List.of(); }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { throw new AssertionError("No sidecar transforms allowed"); }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { throw new AssertionError("No sidecar transforms allowed"); }
}
