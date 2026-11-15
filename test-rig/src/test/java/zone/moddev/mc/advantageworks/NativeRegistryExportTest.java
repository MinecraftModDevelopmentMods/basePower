package zone.moddev.mc.advantageworks;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

/** Byte-bound fixture guards; no server, entity ticks, or user world. */
public class NativeRegistryExportTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = RecipeUnlockProbe.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception exception) throw exception;
            throw failure;
        }
    }

    private Path path(String name) { return temporary.getRoot().toPath().resolve(name); }

    private static void write(Path path, byte[] bytes) throws Exception {
        invoke("writeMatchingSnapshot", new Class<?>[] {Path.class, byte[].class}, path, bytes);
    }

    @Test public void newSnapshotContainsExactBytes() throws Exception {
        Path path = path("new.registry.bin");
        byte[] bytes = {1, 3, 5, 7};
        write(path, bytes);
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @Test public void identicalSnapshotIsReusedWithoutTouchingIt() throws Exception {
        Path path = path("identical.registry.bin");
        byte[] bytes = {2, 4, 6};
        write(path, bytes);
        Files.setLastModifiedTime(path, FileTime.fromMillis(123456000));
        FileTime before = Files.getLastModifiedTime(path);
        write(path, bytes);
        assertEquals(before, Files.getLastModifiedTime(path));
        assertArrayEquals(bytes, Files.readAllBytes(path));
    }

    @Test public void differingSnapshotFailsWithoutOverwritingEvidence() throws Exception {
        Path path = path("mismatch.registry.bin");
        byte[] original = {10, 20, 30};
        write(path, original);
        assertThrows(IllegalStateException.class, () -> write(path, new byte[] {10, 20, 31}));
        assertArrayEquals(original, Files.readAllBytes(path));
    }

    @Test public void differentBootLabelsMayHaveDifferentNativeIds() throws Exception {
        Path first = temporary.getRoot().toPath().resolve((Path) invoke("registrySnapshotPath", new Class<?>[] {String.class}, "normal-sweep"));
        Path restart = temporary.getRoot().toPath().resolve((Path) invoke("registrySnapshotPath", new Class<?>[] {String.class}, "normal-restart"));
        byte[] firstIds = {1, 2, 3}, restartIds = {3, 2, 1};
        write(first, firstIds);
        write(restart, restartIds);
        assertNotEquals(first, restart);
        assertArrayEquals(firstIds, Files.readAllBytes(first));
        assertArrayEquals(restartIds, Files.readAllBytes(restart));
    }

    @Test public void snapshotFilenameIsBoundToTheExactSafeLabel() throws Exception {
        assertEquals(Path.of("recipes-normal-sweep.registry.bin"),
                invoke("registrySnapshotPath", new Class<?>[] {String.class}, "normal-sweep"));
        for (String label : new String[] {"../normal", "normal.bin", "normal/restore", ""}) {
            assertThrows(IllegalStateException.class, () -> invoke("registrySnapshotPath", new Class<?>[] {String.class}, label));
        }
    }

    @Test public void emptyExistingSnapshotFailsClosed() throws Exception {
        Path path = path("empty.registry.bin");
        Files.createFile(path);
        assertThrows(IllegalStateException.class, () -> write(path, new byte[] {1}));
        assertEquals(0, Files.size(path));
    }

    @Test public void directoryCannotBecomeSnapshot() throws Exception {
        Path path = Files.createDirectory(path("directory.registry.bin"));
        assertThrows(IllegalStateException.class, () -> write(path, new byte[] {1}));
        assertTrue(Files.isDirectory(path));
    }

    @Test public void existingPacketAndReportEachRejectWithoutOverwriting() throws Exception {
        Path packet = path("recipes-mode.bin"), report = path("recipes-mode.json");
        Class<?>[] types = {Path.class, Path.class};
        invoke("requireNewRecipeOutputs", types, packet, report);
        Files.write(packet, new byte[] {42});
        assertThrows(IllegalStateException.class, () -> invoke("requireNewRecipeOutputs", types, packet, report));
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(packet));
        Path freshPacket = path("recipes-other.bin");
        Files.writeString(report, "retained receipt");
        assertThrows(IllegalStateException.class, () -> invoke("requireNewRecipeOutputs", types, freshPacket, report));
        assertEquals("retained receipt", Files.readString(report));
        assertFalse(Files.exists(freshPacket));
    }

    @Test public void packetBytesRequireExactContentAndLength() throws Exception {
        Class<?>[] types = {byte[].class, byte[].class};
        invoke("requirePacketBytes", types, new byte[] {1, 2}, new byte[] {1, 2});
        assertThrows(IllegalStateException.class, () -> invoke("requirePacketBytes", types, new byte[] {1, 3}, new byte[] {1, 2}));
        assertThrows(IllegalStateException.class, () -> invoke("requirePacketBytes", types, new byte[] {1, 2, 0}, new byte[] {1, 2}));
    }

    @Test public void receiptHashIsStandardSha256() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                invoke("sha256", new Class<?>[] {byte[].class}, "abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
