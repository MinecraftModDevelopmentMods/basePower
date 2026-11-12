package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.BeforeClass;
import org.junit.Test;

public class Native12111HelperContractTest {
    private static final String SHA = "272085E5730D6C95525017714BC496BC6AF358F35ED6FDF8678A428F740D4EF5";

    @BeforeClass public static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test public void targetResourceIsTheExactIndependentNativeControl() throws Exception {
        byte[] bytes = Native117StoppedEntitiesSnapshotV2.contract();
        assertEquals(SHA, Native117StoppedEntitiesSnapshotV2.sha(bytes));
        JsonObject control = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("native262-leash-knot-nosave-v1", control.get("policy").getAsString());
        assertEquals(SharedConstants.getCurrentVersion().name(), control.get("minecraftVersion").getAsString());
        assertEquals(SharedConstants.getCurrentVersion().dataVersion().version(), control.get("dataVersion").getAsInt());
        assertEquals("65.1.0", control.get("forgeVersion").getAsString());
        for (String key : List.of("noPower", "powerAccessTransformerAbsent", "noHarness", "noServer", "noTicks", "nativeMethodsUnmodified",
                "observationalUuidPreserved", "observationalStateUnchanged", "refusedWritersLeaveNoPayload")) {
            assertTrue(key, control.get(key).getAsBoolean());
        }
        assertFalse(control.get("canSerialize").getAsBoolean());
        assertFalse(control.get("rootWriterAccepted").getAsBoolean());
        assertFalse(control.get("passengerWriterAccepted").getAsBoolean());
        assertFalse(control.get("encodedIdPresent").getAsBoolean());
        assertTrue(control.get("storageSelectorAccepts").getAsBoolean());
    }

    @Test public void evidenceHashesBindToTheActualPinnedClassesAndPrimarySources() throws Exception {
        JsonObject control = JsonParser.parseString(new String(Native117StoppedEntitiesSnapshotV2.contract(), StandardCharsets.UTF_8)).getAsJsonObject();
        Path engine = Path.of(Entity.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertEquals("Loaded engine: " + engine, control.get("engineJarSha256").getAsString(), sha(Files.readAllBytes(engine)));
        String name = engine.getFileName().toString();
        Path sources = engine.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar");
        assertEquals(control.get("engineSourcesSha256").getAsString(), sha(Files.readAllBytes(sources)));
        try (ZipFile zip = new ZipFile(sources.toFile())) {
            for (Class<?> type : List.of(Entity.class, EntityType.class, LeashFenceKnotEntity.class, BlockStateData.class)) {
                String path = type.getName().replace('.', '/');
                try (InputStream input = type.getResourceAsStream("/" + path + ".class")) {
                    assertNotNull(input);
                    assertEquals(control.getAsJsonObject("nativeClassSha256").get(type.getName()).getAsString(), sha(input.readAllBytes()));
                }
                try (InputStream input = zip.getInputStream(zip.getEntry(path + ".java"))) {
                    assertEquals(control.getAsJsonObject("nativeSourceSha256").get(path + ".java").getAsString(), sha(input.readAllBytes()));
                }
            }
        }
        for (var entry : control.getAsJsonObject("controlSourceSha256").entrySet()) {
            assertEquals(entry.getKey(), entry.getValue().getAsString(), sha(Files.readAllBytes(Path.of(entry.getKey()))));
        }
    }

    @Test public void nativeArchiveHasNotInheritedThePowerAccessTransformer() throws Exception {
        assertEquals(Modifier.PRIVATE | Modifier.STATIC | Modifier.FINAL,
                BlockStateData.class.getDeclaredField("MAP").getModifiers());
    }

    @Test public void nativeSaveSelectionIsNotConfusedWithNativeWriterEligibility() throws Exception {
        var world = Native117EntityContextTest.world(Level.NETHER);
        var knot = new LeashFenceKnotEntity(world, new BlockPos(154, 100, 8));
        var before = NativeActorIo.saveWithoutId(knot).copy();
        assertTrue(knot.shouldBeSaved());
        assertFalse(net.minecraft.world.entity.EntityTypes.LEASH_KNOT.canSerialize());
        assertNull(knot.getEncodeId());
        TagValueOutput root = TagValueOutput.createWithContext(NativeActorIo.reporter("root control"), world.registryAccess());
        TagValueOutput passenger = TagValueOutput.createWithContext(NativeActorIo.reporter("passenger control"), world.registryAccess());
        assertFalse(knot.save(root));
        assertFalse(knot.saveAsPassenger(passenger));
        assertTrue(root.buildResult().isEmpty());
        assertTrue(passenger.buildResult().isEmpty());
        assertThrows(IllegalStateException.class, () -> NativeActorIo.save(knot));
        assertThrows(IllegalStateException.class, () -> NativeActorIo.saveAsPassenger(knot));
        assertEquals(before, NativeActorIo.saveWithoutId(knot));
        assertEquals(0, knot.tickCount);
    }

    private static String sha(byte[] bytes) throws Exception { return Native117StoppedEntitiesSnapshotV2.sha(bytes); }
}
