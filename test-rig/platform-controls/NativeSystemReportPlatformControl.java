import com.google.gson.Gson;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.minecraft.SharedConstants;
import net.minecraft.SystemReport;

/** Reproduces host diagnostics without Advantage, a world, a server or entity ticks. */
public final class NativeSystemReportPlatformControl {
    public static void main(String[] args) throws Exception {
        for (String name : List.of("zone.moddev.mc.basepower.PowerAdvantage",
                "zone.moddev.mc.advantageworks.NativeActorIo")) {
            try {
                Class.forName(name);
                throw new AssertionError("Advantage code is present: " + name);
            } catch (ClassNotFoundException expected) { }
        }
        for (String key : List.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"))
            if (!System.getenv().getOrDefault(key, "").isBlank())
                throw new AssertionError("Inherited native control option " + key);
        SharedConstants.tryDetectVersion();
        Path root = Path.of(args[0]);
        Files.createDirectory(root);
        String report = new SystemReport().toLineSeparatedString();
        Files.writeString(root.resolve("native-system-report.txt"), report);
        boolean missing = new oshi.SystemInfo().getOperatingSystem().getCurrentProcess() == null;
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("scope", "INDEPENDENT_NATIVE_HOST_DIAGNOSTICS_NOT_GAMEPLAY");
        proof.put("minecraftVersion", SharedConstants.getCurrentVersion().name());
        proof.put("targetDataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        proof.put("mainClass", "NativeSystemReportPlatformControl");
        proof.put("currentProcessMissing", missing);
        proof.put("noPower", true);
        proof.put("noHarness", true);
        proof.put("noServer", true);
        proof.put("noWorld", true);
        proof.put("noTicks", true);
        proof.put("inheritedJvmEnvironmentAbsent", true);
        proof.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        proof.put("nativeClassSha256", NativePlatformSupport.classHashes(SystemReport.class, oshi.SystemInfo.class));
        NativePlatformSupport.sourceEvidence(proof, SystemReport.class);
        proof.put("controlSourceSha256", NativePlatformSupport.sha(Files.readAllBytes(
                Path.of("platform-controls/NativeSystemReportPlatformControl.java"))));
        List<Map<String, String>> archives = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator))) {
            Path path = Path.of(entry).toAbsolutePath().normalize();
            if (Files.isRegularFile(path)) archives.add(Map.of("path", path.toString(), "sha256",
                    NativePlatformSupport.sha(Files.readAllBytes(path))));
        }
        proof.put("classpath", archives);
        Files.writeString(root.resolve("receipt.json"), new Gson().toJson(proof) + "\n");
        if (!missing || !report.contains("Process Loads: ~~ERROR~~ NullPointerException")
                || !report.contains("Process Virtual Size (MiB): ~~ERROR~~ NullPointerException")
                || !report.contains("Process Resident Size (MiB): ~~ERROR~~ NullPointerException"))
            throw new AssertionError("Host failure was not independently reproduced");
        System.out.println("NATIVE_SYSTEM_REPORT_CONTROL_DONE no_mods=true no_server=true no_ticks=true");
    }
}
