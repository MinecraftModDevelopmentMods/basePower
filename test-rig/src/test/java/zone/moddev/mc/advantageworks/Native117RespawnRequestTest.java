package zone.moddev.mc.advantageworks;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class Native117RespawnRequestTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String hash(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) result.append(String.format("%02X", value & 255));
        return result.toString();
    }

    private Path fixture() throws Exception {
        Path root = temporary.newFolder().toPath();
        Files.writeString(root.resolve("dimension-fixture.json"),
                "{\"dimension\":1,\"playerName\":\"Witness\",\"playerUuid\":\"e036830e-303d-3ac3-bd23-062b72eed895\","
                + "\"geometry\":{\"x\":176},\"respawn\":{\"retained\":\"base-only\"}}\n");
        Files.createDirectory(root.resolve("respawn-requests"));
        return root.resolve("dimension-fixture.json");
    }

    private JsonObject request(Path fixture, String label) throws Exception {
        JsonObject value = new JsonObject();
        value.addProperty("protocol", "native117-respawn-request-v1");
        value.addProperty("id", label);
        value.addProperty("baseFixtureSha256", hash(Files.readAllBytes(fixture)));
        value.addProperty("keepInventory", true);
        value.addProperty("bedMode", "bed");
        return value;
    }

    private Path write(Path fixture, String label, JsonObject request) throws Exception {
        Path path = fixture.getParent().resolve("respawn-requests").resolve(label + ".json");
        Files.writeString(path, request.toString(), StandardCharsets.UTF_8);
        return path;
    }

    private JsonObject read(Path fixture, String label) throws Exception {
        Method method = DimensionEntityProbe.class.getDeclaredMethod("respawnRequest", Path.class, String.class);
        method.setAccessible(true);
        try { return (JsonObject) method.invoke(null, fixture, label); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    @Test public void immutableRequestMergesOnlyOptionsAndReportsActualRawHashes() throws Exception {
        Path fixture = fixture(); String label = "respawn-keep-bed";
        byte[] before = Files.readAllBytes(fixture);
        Path path = write(fixture, label, request(fixture, label));
        JsonObject result = read(fixture, label);
        JsonObject definition = result.getAsJsonObject("definition");
        JsonObject expected = new JsonParser().parse(new String(before, StandardCharsets.UTF_8)).getAsJsonObject();
        expected.getAsJsonObject("respawn").addProperty("keepInventory", true);
        expected.getAsJsonObject("respawn").addProperty("bedMode", "bed");
        assertEquals(expected, definition);
        JsonObject evidence = result.getAsJsonObject("evidence");
        assertEquals(label, evidence.get("id").getAsString());
        assertEquals("native117-respawn-request-v1", evidence.get("protocol").getAsString());
        assertEquals("respawn-requests/" + label + ".json", evidence.get("path").getAsString());
        assertEquals(hash(before), evidence.get("baseFixtureSha256").getAsString());
        assertEquals(hash(Files.readAllBytes(path)), evidence.get("sha256").getAsString());
        assertArrayEquals(before, Files.readAllBytes(fixture));
    }

    @Test public void wrongProtocolIdBaseHashAndUnknownFieldsAreRejected() throws Exception {
        for (String field : new String[]{"protocol", "id", "baseFixtureSha256", "dimension"}) {
            Path fixture = fixture(); String label = "respawn-keep-bed";
            JsonObject value = request(fixture, label); value.addProperty(field, "wrong");
            write(fixture, label, value);
            assertThrows(field, IllegalArgumentException.class, () -> read(fixture, label));
        }
    }

    @Test public void booleansModesAndLabelOptionsUseExactTypesAndValues() throws Exception {
        for (String invalid : new String[]{"1", "\"true\"", "null", "false"}) {
            Path fixture = fixture(); String label = "respawn-keep-bed";
            JsonObject value = request(fixture, label);
            value.add("keepInventory", new JsonParser().parse(invalid));
            write(fixture, label, value);
            assertThrows(IllegalArgumentException.class, () -> read(fixture, label));
        }
        Path fixture = fixture(); String label = "respawn-keep-bed";
        JsonObject value = request(fixture, label); value.addProperty("bedMode", "forced");
        write(fixture, label, value);
        assertThrows(IllegalArgumentException.class, () -> read(fixture, label));
    }

    @Test public void traversalOtherLabelsMissingFilesAndDuplicateKeysCannotBeConsumed() throws Exception {
        Path fixture = fixture(); String label = "respawn-keep-bed";
        for (String invalid : new String[]{"../outside", "respawn", "respawn-keep-bed.json", "respawn-keep-other"})
            assertThrows(IllegalArgumentException.class, () -> read(fixture, invalid));
        assertThrows(Exception.class, () -> read(fixture, label));
        Path path = write(fixture, label, request(fixture, label));
        String text = Files.readString(path);
        Files.writeString(path, text.substring(0, text.length() - 1) + ",\"keepInventory\":true}");
        assertThrows(IllegalArgumentException.class, () -> read(fixture, label));
    }

    @Test public void consumedRequestDoesNotHoldOpenTheRootOrItsOwnFile() throws Exception {
        Path fixture = fixture(); String label = "respawn-keep-bed";
        Path path = write(fixture, label, request(fixture, label));
        try (java.io.InputStream rootReader = Files.newInputStream(fixture)) {
            read(fixture, label);
            assertTrue(rootReader.read() >= 0);
        }
        Files.move(path, path.resolveSibling("closed-request.json"));
        Files.move(fixture, fixture.resolveSibling("closed-fixture.json"));
    }

    @Test public void everyExistingCaseLabelRetainsItsDimensionAndExactOptions() throws Exception {
        for (int dimension : new int[]{-1, 1}) for (String boot : new String[]{"respawn", "respawn-restart"})
            for (boolean keep : new boolean[]{true, false}) for (String mode : new String[]{"forced", "bed", "missing"}) {
                Path fixture = fixture();
                JsonObject base = new JsonParser().parse(Files.readString(fixture)).getAsJsonObject();
                base.addProperty("dimension", dimension); Files.writeString(fixture, base.toString());
                byte[] bytes = Files.readAllBytes(fixture);
                String label = boot + "-" + (keep ? "keep" : "drop") + "-" + mode;
                JsonObject value = request(fixture, label);
                value.addProperty("keepInventory", keep); value.addProperty("bedMode", mode);
                write(fixture, label, value);
                JsonObject result = read(fixture, label), merged = result.getAsJsonObject("definition");
                assertEquals(dimension, merged.get("dimension").getAsInt());
                assertEquals(keep, merged.getAsJsonObject("respawn").get("keepInventory").getAsBoolean());
                assertEquals(mode, merged.getAsJsonObject("respawn").get("bedMode").getAsString());
                assertArrayEquals(bytes, Files.readAllBytes(fixture));
            }
    }

    @Test public void malformedOversizedMissingAndTrailingRequestFieldsAreRejected() throws Exception {
        Path fixture = fixture(); String label = "respawn-keep-bed";
        JsonObject value = request(fixture, label);
        Path path = write(fixture, label, value);
        for (String invalid : new String[]{"{", value + " {}", value + " ".repeat(4097)}) {
            Files.writeString(path, invalid);
            assertThrows(IllegalArgumentException.class, () -> read(fixture, label));
        }
        value.remove("bedMode"); Files.writeString(path, value.toString());
        assertThrows(IllegalArgumentException.class, () -> read(fixture, label));
    }

    @Test public void commandDispatchConsumesTheValidatedDefinitionAndAcknowledgesBeforeWriting() throws Exception {
        String source = Files.readString(Path.of("src/main/java/zone/moddev/mc/advantageworks/DimensionEntityProbe.java"));
        String execute = source.substring(source.indexOf("private void execute("), source.indexOf("private static JsonObject definition()"));
        assertTrue(execute.contains("operation.equals(\"respawn\") ? respawnRequest(DEFINITION.toPath(), label) : null"));
        assertTrue(execute.contains("request == null ? definition() : request.getAsJsonObject(\"definition\")"));
        assertTrue(execute.indexOf("respawnRequest(DEFINITION") < execute.indexOf("snapshot(server, world, definition, operation)"));
        int acknowledgement = execute.indexOf("result.add(\"respawnRequest\", request.getAsJsonObject(\"evidence\"))");
        assertTrue(acknowledgement >= 0 && acknowledgement < execute.indexOf("Files.write("));
    }
}
