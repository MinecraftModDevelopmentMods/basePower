package zone.moddev.mc.basepower.compat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.OutputStream;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LegacyLevelDataTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private NBTTagCompound original() {
        NBTTagCompound root = new NBTTagCompound(), forge = new NBTTagCompound();
        NBTTagList fluids = new NBTTagList();
        for (String id : new String[] { "poweradvantage:crude_oil", "minecraft:water", "basemetals:mercury",
                "poweradvantage:refined_oil", "othermod:crude_oil" }) fluids.appendTag(new NBTTagString(id));
        forge.setTag("DefaultFluidList", fluids);
        forge.setInteger("Unrelated", 12345);
        root.setTag("Forge", forge);
        root.setString("PlayerCustomText", "poweradvantage:crude_oil");
        root.setIntArray("Energy", new int[] { 0x3f800001, 0x44a00000 });
        return root;
    }

    @Test public void onlyTheTwoOwnedFluidOwnerNamesChange() {
        NBTTagCompound root = original();
        assertTrue(LegacyLevelData.remapFluidDefaults(root));
        NBTTagList fluids = root.getCompoundTag("Forge").getTagList("DefaultFluidList", 8);
        assertEquals(5, fluids.tagCount());
        assertEquals("basepower:crude_oil", fluids.getStringTagAt(0));
        assertEquals("minecraft:water", fluids.getStringTagAt(1));
        assertEquals("basemetals:mercury", fluids.getStringTagAt(2));
        assertEquals("basepower:refined_oil", fluids.getStringTagAt(3));
        assertEquals("othermod:crude_oil", fluids.getStringTagAt(4));
        assertEquals("poweradvantage:crude_oil", root.getString("PlayerCustomText"));
        assertEquals(12345, root.getCompoundTag("Forge").getInteger("Unrelated"));
        assertArrayEquals(new int[] {0x3f800001, 0x44a00000}, root.getIntArray("Energy"));
        assertFalse(LegacyLevelData.remapFluidDefaults(root));
    }

    @Test public void bothHeadersHaveExactBackupsAndAByteStableSecondPass() throws Exception {
        Path world = temporary.newFolder().toPath();
        for (String name : new String[] { "level.dat", "level.dat_old" }) {
            try (OutputStream output = Files.newOutputStream(world.resolve(name))) {
                CompressedStreamTools.writeCompressed(original(), output);
            }
        }
        byte[] before = Files.readAllBytes(world.resolve("level.dat"));
        LegacyLevelData.migrateWorld(world);
        byte[] migrated = Files.readAllBytes(world.resolve("level.dat"));
        assertFalse(java.util.Arrays.equals(before, migrated));
        try (java.util.stream.Stream<Path> backups = Files.list(world.resolve("basepower-migration-backups"))) {
            Path[] files = backups.toArray(Path[]::new);
            assertEquals(2, files.length);
            for (Path backup : files) assertArrayEquals(before, Files.readAllBytes(backup));
        }
        LegacyLevelData.migrateWorld(world);
        assertArrayEquals(migrated, Files.readAllBytes(world.resolve("level.dat")));
        assertArrayEquals(migrated, Files.readAllBytes(world.resolve("level.dat_old")));
    }

    @Test public void aWorldWithoutOwnedDefaultsIsNotRewritten() throws Exception {
        Path world = temporary.newFolder().toPath();
        NBTTagCompound root = new NBTTagCompound(); root.setString("Untouched", "value");
        try (OutputStream output = Files.newOutputStream(world.resolve("level.dat"))) {
            CompressedStreamTools.writeCompressed(root, output);
        }
        byte[] before = Files.readAllBytes(world.resolve("level.dat"));
        LegacyLevelData.migrateWorld(world);
        assertArrayEquals(before, Files.readAllBytes(world.resolve("level.dat")));
        assertFalse(Files.exists(world.resolve("basepower-migration-backups")));
    }

    @Test public void savedModOwnersChangeOnlyForInstalledReplacements() {
        NBTTagCompound root = new NBTTagCompound(), fml = new NBTTagCompound();
        NBTTagList mods = new NBTTagList();
        for (String name : new String[] { "poweradvantage", "steamadvantage", "electricadvantage", "basemetals" }) {
            NBTTagCompound mod = new NBTTagCompound(); mod.setString("ModId", name); mod.setString("ModVersion", "old-version");
            mods.appendTag(mod);
        }
        fml.setTag("ModList", mods); root.setTag("FML", fml);
        java.util.Set<String> installed = new java.util.HashSet<>(java.util.Arrays.asList("basepower", "basesteam"));
        assertTrue(LegacyLevelData.remapModList(root, installed));
        assertEquals("basepower", mods.getCompoundTagAt(0).getString("ModId"));
        assertEquals("basesteam", mods.getCompoundTagAt(1).getString("ModId"));
        assertEquals("electricadvantage", mods.getCompoundTagAt(2).getString("ModId"));
        assertEquals("basemetals", mods.getCompoundTagAt(3).getString("ModId"));
        for (int index = 0; index < 4; index++) assertEquals("old-version", mods.getCompoundTagAt(index).getString("ModVersion"));
        assertFalse(LegacyLevelData.remapModList(root, installed));
    }

    @Test public void ambiguousOldAndNewOwnersFailBeforeChangingTheHeader() throws Exception {
        Path world = temporary.newFolder().toPath();
        NBTTagCompound root = original(), fml = new NBTTagCompound();
        NBTTagList mods = new NBTTagList();
        for (String name : new String[] { "poweradvantage", "basepower" }) {
            NBTTagCompound mod = new NBTTagCompound(); mod.setString("ModId", name); mods.appendTag(mod);
        }
        fml.setTag("ModList", mods); root.setTag("FML", fml);
        Path header = world.resolve("level.dat");
        try (OutputStream output = Files.newOutputStream(header)) {
            CompressedStreamTools.writeCompressed(root, output);
        }
        byte[] before = Files.readAllBytes(header);
        try {
            LegacyLevelData.migrateWorld(world);
            fail("An ambiguous old/new installation must not be silently accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("both poweradvantage and basepower"));
        }
        assertArrayEquals(before, Files.readAllBytes(header));
        assertFalse(Files.exists(world.resolve("basepower-migration-backups")));
    }
}
