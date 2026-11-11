package zone.moddev.orderprobe;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/** Read-only, reproducible per-handler hashes from the already sealed real exports. */
public final class BodyEvidenceDump {
    public static void main(String[] args) throws Exception {
        JsonObject pins = ProbeGuard.read(Path.of(args[0]));
        Path runtime = Path.of(args[1]), exports = Path.of(args[2]);
        ProbeGuard.verifyProduction(pins, runtime.resolve("mods"));
        Method hash = OrderProbeAudit.class.getDeclaredMethod("codeHash", MethodNode.class, String.class);
        hash.setAccessible(true);
        Map<String, Object> rows = new LinkedHashMap<>();
        for (String target : List.of("net/minecraft/world/level/chunk/storage/SimpleRegionStorage",
                "net/minecraft/world/level/storage/LevelStorageSource", "net/minecraft/server/level/WorldGenRegion")) {
            ClassNode woven = ProbeGuard.node(Files.readAllBytes(exports.resolve(target + ".class")));
            for (String mod : List.of("basepower", "basemetals")) {
                String shortName = target.substring(target.lastIndexOf('/') + 1);
                String sourceName = "zone/moddev/mc/" + mod + "/mixin/" + shortName + "Mixin";
                Path jar = runtime.resolve("mods").resolve(pins.getAsJsonObject("mods").getAsJsonObject(mod).get("file").getAsString());
                ClassNode source = ProbeGuard.node(ProbeGuard.entry(jar, sourceName + ".class"));
                for (String suffix : List.of("prepareChunk", "finishChunk", "prepareInventories", "prepareWorld", "protectTerrain")) {
                    String name = mod + "$" + suffix;
                    List<MethodNode> originals = source.methods.stream().filter(row -> row.name.equals(name)).toList();
                    if (originals.isEmpty()) continue;
                    ProbeGuard.require(originals.size() == 1, "duplicate source handler");
                    MethodNode original = originals.getFirst();
                    String rawHash = (String) hash.invoke(null, original, source.name);
                    String transformation = "none";
                    if (suffix.equals("protectTerrain")) {
                        var code = Arrays.stream(original.instructions.toArray()).filter(row -> row.getOpcode() >= 0).toList();
                        ProbeGuard.require(code.get(1) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
                                && cast.desc.equals(target), "reviewed source receiver cast changed");
                        original.instructions.remove(code.get(1));
                        transformation = "exact receiver CHECKCAST elision: MixinTargetContext.transformTypeNode";
                    }
                    MethodNode observed = woven.methods.stream().filter(row -> row.name.endsWith(name)).findFirst().orElseThrow();
                    String expected = (String) hash.invoke(null, original, source.name);
                    String actual = (String) hash.invoke(null, observed, woven.name);
                    ProbeGuard.require(expected.equals(actual), "per-handler body mismatch " + name);
                    rows.put(name, Map.of("sourceClass", sourceName, "sourceRawCodeSha256", rawHash,
                            "expectedWovenCodeSha256", expected, "observedCodeSha256", actual,
                            "expectedTransformation", transformation, "generatedHandler", observed.name));
                }
            }
        }
        ProbeGuard.require(rows.size() == 9, "missing required handler evidence");
        Files.writeString(Path.of(args[3]), new Gson().toJson(rows) + "\n", StandardOpenOption.CREATE_NEW);
        System.out.println("NINE_PINNED_HANDLER_BODIES_MATCH");
    }
}
