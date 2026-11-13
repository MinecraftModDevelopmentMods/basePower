import com.google.gson.Gson;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independently reproduces actual native Netty transport selection on this Windows host. */
public final class NativeNettyTransportPlatformControl {
    public static void main(String[] args) throws Exception {
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"))
            if (!System.getenv().getOrDefault(key, "").isBlank()) throw new AssertionError("Inherited JVM channel: " + key);
        try {
            Class.forName("zone.moddev.mc.basepower.PowerAdvantage");
            throw new AssertionError("Power must be absent");
        } catch (ClassNotFoundException expected) { }
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("engineProtocol", "NATIVE66");
        proof.put("scope", "INDEPENDENT_NATIVE_NETTY_TRANSPORT_NOT_SERVER_OR_GAMEPLAY");
        proof.put("noPower", true);
        proof.put("noHarness", true);
        proof.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        proof.put("javaVersion", Runtime.version().toString());
        Map<String, Object> transports = new LinkedHashMap<>();
        for (String name : List.of("KQueue", "Epoll")) {
            String packageName = name.equals("KQueue") ? "kqueue" : "epoll";
            Class<?> type = Class.forName("io.netty.channel." + packageName + "." + name);
            boolean available = (Boolean) type.getMethod("isAvailable").invoke(null);
            Throwable cause = (Throwable) type.getMethod("unavailabilityCause").invoke(null);
            String expected = name.equals("KQueue") ? "Only supported on OSX/BSD" : "Only supported on Linux";
            if (available || !(cause instanceof ExceptionInInitializerError)
                    || !(cause.getCause() instanceof IllegalStateException) || !expected.equals(cause.getCause().getMessage()))
                throw new AssertionError("Unexpected native Windows transport result: " + name + ": " + cause);
            cause.printStackTrace(System.out);
            Path archive = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
            transports.put(name, Map.of("available", available, "cause", cause.toString(),
                    "rootCause", cause.getCause().toString(), "classSha256", NativePlatformSupport.classHash(type),
                    "archive", archive.toString(), "archiveSha256", NativePlatformSupport.sha(Files.readAllBytes(archive))));
        }
        proof.put("transports", transports);
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        Files.writeString(root.resolve("receipt.json"), new Gson().toJson(proof) + "\n");
        System.out.println("NATIVE_NETTY_TRANSPORT_CONTROL_PASS");
    }
}
