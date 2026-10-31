package zone.moddev.mc.advantageworks.unit;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/** Runs Forge's own token transform for plain JUnit, without replacing its capabilities. */
public final class ForgeCapabilityBootstrap {
    public static void premain(String options, Instrumentation instrumentation) {
        instrumentation.addTransformer(new ClassFileTransformer() {
            @Override public byte[] transform(ClassLoader loader, String name, Class<?> type,
                    ProtectionDomain domain, byte[] bytes) {
                if (loader == null || name == null || !(name.contains("$")
                        || name.equals("net/minecraftforge/common/capabilities/CapabilityToken"))) return null;
                try {
                    Class<?> readerType = Class.forName("org.objectweb.asm.ClassReader", true, loader);
                    Object reader = readerType.getConstructor(byte[].class).newInstance((Object)bytes);
                    String parent = (String)readerType.getMethod("getSuperName").invoke(reader);
                    String token = "net/minecraftforge/common/capabilities/CapabilityToken";
                    if (!token.equals(name) && !token.equals(parent)) return null;
                    Class<?> nodeType = Class.forName("org.objectweb.asm.tree.ClassNode", true, loader);
                    Object node = nodeType.getConstructor().newInstance();
                    Class<?> visitorType = Class.forName("org.objectweb.asm.ClassVisitor", true, loader);
                    readerType.getMethod("accept", visitorType, int.class).invoke(reader, node, 0);
                    Class<?> asmType = Class.forName("org.objectweb.asm.Type", true, loader);
                    Object asmName = asmType.getMethod("getObjectType", String.class).invoke(null, name);
                    Class<?> phaseType = Class.forName(
                            "cpw.mods.modlauncher.serviceapi.ILaunchPluginService$Phase", true, loader);
                    Object phase = phaseType.getField("BEFORE").get(null);
                    Class<?> pluginType = Class.forName(
                            "net.minecraftforge.common.asm.CapabilityTokenSubclass", true, loader);
                    pluginType.getMethod("processClassWithFlags", phaseType, nodeType, asmType, String.class)
                            .invoke(pluginType.getConstructor().newInstance(), phase, node, asmName, "junit");
                    Class<?> writerType = Class.forName("org.objectweb.asm.ClassWriter", true, loader);
                    Object writer = writerType.getConstructor(int.class).newInstance(1);
                    nodeType.getMethod("accept", visitorType).invoke(node, writer);
                    return (byte[])writerType.getMethod("toByteArray").invoke(writer);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Cannot run Forge's native capability transform for " + name, failure);
                }
            }
        });
    }
}
