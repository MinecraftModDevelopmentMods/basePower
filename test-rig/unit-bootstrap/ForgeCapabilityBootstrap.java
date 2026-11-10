package zone.moddev.mc.advantageworks.unit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Optional;

/** Runs Forge's own token and event transforms for plain JUnit. */
public final class ForgeCapabilityBootstrap {
    private static final String MOD_LOADER = "net/minecraftforge/fml/ModLoader";
    private static final String AGENT = "zone/moddev/mc/advantageworks/unit/ForgeCapabilityBootstrap";

    public static void premain(String options, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (loader == null || name == null) return null;
                boolean event = name.startsWith("net/minecraftforge/network/NetworkEvent")
                        || name.equals("net/minecraftforge/registries/MissingMappingsEvent");
                boolean modLoader = MOD_LOADER.equals(name);
                String token = "net/minecraftforge/common/capabilities/CapabilityToken";
                if (!event && !modLoader && !(name.contains("$") || name.equals(token))) return null;
                if (!event && !modLoader && !token.equals(name)
                        && loader.getResource("org/objectweb/asm/ClassReader.class") == null) return null;
                try {
                    Class<?> readerType = Class.forName("org.objectweb.asm.ClassReader", true, loader);
                    Object reader = readerType.getConstructor(byte[].class).newInstance((Object)bytes);
                    String parent = (String)readerType.getMethod("getSuperName").invoke(reader);
                    if (!event && !modLoader && !token.equals(name) && !token.equals(parent)) return null;
                    Class<?> nodeType = Class.forName("org.objectweb.asm.tree.ClassNode", true, loader);
                    Object node = nodeType.getConstructor().newInstance();
                    Class<?> visitorType = Class.forName("org.objectweb.asm.ClassVisitor", true, loader);
                    readerType.getMethod("accept", visitorType, int.class).invoke(reader, node, 0);
                    Class<?> asmType = Class.forName("org.objectweb.asm.Type", true, loader);
                    Object asmName = asmType.getMethod("getObjectType", String.class).invoke(null, name);
                    if (modLoader) {
                        addLoaderFixture(loader, nodeType, node, asmName);
                    } else if (event) {
                        Class<?> transformer = Class.forName("net.minecraftforge.eventbus.EventSubclassTransformer", true, loader);
                        var transformed = (java.util.Optional<?>) transformer.getMethod("transform", nodeType, asmType)
                                .invoke(transformer.getConstructor().newInstance(), node, asmName);
                        if (transformed.isEmpty()) return null;
                        node = transformed.get();
                    } else {
                        Class<?> phaseType = Class.forName(
                                "cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase", true, loader);
                        Object phase = phaseType.getField("BEFORE").get(null);
                        Class<?> pluginType = Class.forName(
                                "net.minecraftforge.fml.common.asm.CapabilityTokenSubclass", true, loader);
                        pluginType.getMethod("processClassWithFlags", phaseType, nodeType, asmType, String.class)
                                .invoke(pluginType.getConstructor().newInstance(), phase, node, asmName, "junit");
                    }
                    Class<?> writerType = Class.forName("org.objectweb.asm.ClassWriter", true, loader);
                    Object writer = writerType.getConstructor(int.class).newInstance(1);
                    nodeType.getMethod("accept", visitorType).invoke(node, writer);
                    return (byte[])writerType.getMethod("toByteArray").invoke(writer);
                } catch (ReflectiveOperationException failure) {
                    System.err.println("Forge unit transform failed for " + name + ": " + failure);
                    throw new IllegalStateException("Cannot run Forge's native unit transform for " + name, failure);
                }
            }
        });
    }

    private static void addLoaderFixture(ClassLoader loader, Class<?> nodeType, Object node, Object asmName)
            throws ReflectiveOperationException {
        Class<?> insn = Class.forName("org.objectweb.asm.tree.AbstractInsnNode", true, loader);
        Class<?> list = Class.forName("org.objectweb.asm.tree.InsnList", true, loader);
        Class<?> ldc = Class.forName("org.objectweb.asm.tree.LdcInsnNode", true, loader);
        Class<?> var = Class.forName("org.objectweb.asm.tree.VarInsnNode", true, loader);
        Class<?> method = Class.forName("org.objectweb.asm.tree.MethodInsnNode", true, loader);
        for (Object ctor : (List<?>) nodeType.getField("methods").get(node)) {
            Class<?> type = ctor.getClass();
            if (!"<init>".equals(type.getField("name").get(ctor)) || !"()V".equals(type.getField("desc").get(ctor))) continue;
            Object instructions = type.getField("instructions").get(ctor);
            Object entry = list.getConstructor().newInstance();
            var add = list.getMethod("add", insn);
            var call = method.getConstructor(int.class, String.class, String.class, String.class, boolean.class);
            add.invoke(entry, ldc.getConstructor(Object.class).newInstance(asmName));
            add.invoke(entry, call.newInstance(184, AGENT, "beforeModLoader", "(Ljava/lang/Class;)V", false));
            for (Object instruction : (Object[]) list.getMethod("toArray").invoke(instructions)) {
                if ((int) insn.getMethod("getOpcode").invoke(instruction) != 177) continue; // RETURN
                Object exit = list.getConstructor().newInstance();
                add.invoke(exit, var.getConstructor(int.class, int.class).newInstance(25, 0)); // ALOAD 0
                add.invoke(exit, call.newInstance(184, AGENT, "afterModLoader", "(Ljava/lang/Object;)V", false));
                list.getMethod("insertBefore", insn, list).invoke(instructions, instruction, exit);
            }
            list.getMethod("insert", list).invoke(instructions, entry);
            return;
        }
        throw new NoSuchMethodException("Forge 50 ModLoader()");
    }

    public static void beforeModLoader(Class<?> owner) throws ReflectiveOperationException {
        ClassLoader loader = owner.getClassLoader();
        Class<?> fml = Class.forName("net.minecraftforge.fml.loading.FMLLoader", true, loader);
        if (fml.getMethod("getLoadingModList").invoke(null) != null) {
            throw new IllegalStateException("The JUnit agent must not run inside a real Forge launch");
        }
        Class<?> loading = Class.forName("net.minecraftforge.fml.loading.LoadingModList", true, loader);
        Class<?> earlyError = Class.forName("net.minecraftforge.fml.loading.EarlyLoadingException", true, loader);
        Object emptyDiscovery = loading.getMethod("of", List.class, List.class, earlyError)
                .invoke(null, List.of(), List.of(), null);
        loading.getMethod("setBrokenFiles", List.class).invoke(emptyDiscovery, List.of());
        var listField = fml.getDeclaredField("loadingModList");
        listField.setAccessible(true);
        listField.set(null, emptyDiscovery);
        Class<?> layers = Class.forName("cpw.mods.modlauncher.api.IModuleLayerManager", true, loader);
        Object classpathLayers = Proxy.newProxyInstance(loader, new Class<?>[] {layers}, (proxy, method, args) -> {
            if (method.getName().equals("getLayer")) return Optional.of(ModuleLayer.boot());
            throw new UnsupportedOperationException(method.toString());
        });
        var layerField = fml.getDeclaredField("moduleLayerManager");
        layerField.setAccessible(true);
        layerField.set(null, classpathLayers);
    }

    public static void afterModLoader(Object loader) throws ReflectiveOperationException {
        Class<?> owner = loader.getClass();
        Class<?> mods = Class.forName("net.minecraftforge.fml.ModList", true, owner.getClassLoader());
        Object emptyMods = mods.getMethod("of", List.class, List.class).invoke(null, List.of(), List.of());
        var setLoaded = mods.getDeclaredMethod("setLoadedMods", List.class);
        setLoaded.setAccessible(true);
        setLoaded.invoke(emptyMods, List.of());
        var modList = owner.getDeclaredField("modList");
        modList.setAccessible(true);
        modList.set(loader, emptyMods);
        var valid = owner.getDeclaredField("loadingStateValid");
        valid.setAccessible(true);
        valid.setBoolean(loader, true);
    }
}
