package zone.moddev.mc.basepower.compat;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.init.Bootstrap;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.event.FMLMissingMappingsEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class LegacyIdentityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @BeforeClass public static void bootstrap() { Bootstrap.register(); }

    @Test public void originalConfigIsCopiedOnceAndNewConfigWins() throws Exception {
        Path config = temporary.newFolder().toPath();
        Path old = config.resolve("poweradvantage.cfg"), current = config.resolve("basepower.cfg");
        byte[] original = "options { S:recipe_mode=APOCALYPTIC }".getBytes(StandardCharsets.UTF_8);
        Files.write(old, original);
        LegacyIdentity.migrateConfiguration(current.toFile(), "poweradvantage");
        assertArrayEquals(original, Files.readAllBytes(current));
        byte[] edited = "new user authority".getBytes(StandardCharsets.UTF_8);
        Files.write(current, edited);
        LegacyIdentity.migrateConfiguration(current.toFile(), "poweradvantage");
        assertArrayEquals(edited, Files.readAllBytes(current));
        assertArrayEquals(original, Files.readAllBytes(old));
    }

    @Test public void originalTileLoadsAndSavesUnderTheNewCanonicalIdentity() {
        LegacyIdentity.registerTileEntity(MigrationTile.class, "basepower.test_migration_tile");
        NBTTagCompound source = new NBTTagCompound();
        source.setString("id", "poweradvantage.test_migration_tile");
        source.setInteger("x", 7); source.setInteger("y", 64); source.setInteger("z", -11);
        TileEntity loaded = TileEntity.create(null, source);
        assertTrue(loaded instanceof MigrationTile);
        NBTTagCompound saved = loaded.writeToNBT(new NBTTagCompound());
        assertEquals("basepower.test_migration_tile", saved.getString("id"));
        assertEquals(7, saved.getInteger("x"));
        assertEquals(-11, saved.getInteger("z"));
        assertTrue(TileEntity.create(null, saved) instanceof MigrationTile);
    }

    @Test public void unknownOwnedMappingsFailInsteadOfBeingDiscarded() {
        ListMultimap<String, FMLMissingMappingsEvent.MissingMapping> missing = ArrayListMultimap.create();
        FMLMissingMappingsEvent.MissingMapping owned = new FMLMissingMappingsEvent.MissingMapping(
                GameRegistry.Type.ITEM, new ResourceLocation("poweradvantage", "not_a_real_component"), 30000);
        FMLMissingMappingsEvent.MissingMapping foreign = new FMLMissingMappingsEvent.MissingMapping(
                GameRegistry.Type.ITEM, new ResourceLocation("unrelated", "not_a_real_component"), 30001);
        missing.put("poweradvantage", owned); missing.put("unrelated", foreign);
        LegacyIdentity.remapMissingMappings(new FMLMissingMappingsEvent(missing), "poweradvantage", "basepower");
        assertEquals(FMLMissingMappingsEvent.Action.FAIL, owned.getAction());
        assertEquals(FMLMissingMappingsEvent.Action.DEFAULT, foreign.getAction());
    }

    @Test public void bothBlockAndItemMappingsResolveTheExactRegisteredObject() {
        ListMultimap<String, FMLMissingMappingsEvent.MissingMapping> missing = ArrayListMultimap.create();
        FMLMissingMappingsEvent.MissingMapping block = new FMLMissingMappingsEvent.MissingMapping(
                GameRegistry.Type.BLOCK, new ResourceLocation("legacy_minecraft", "stone"), 2000);
        FMLMissingMappingsEvent.MissingMapping item = new FMLMissingMappingsEvent.MissingMapping(
                GameRegistry.Type.ITEM, new ResourceLocation("legacy_minecraft", "diamond"), 2001);
        missing.put("legacy_minecraft", block); missing.put("legacy_minecraft", item);
        LegacyIdentity.remapMissingMappings(new FMLMissingMappingsEvent(missing), "legacy_minecraft", "minecraft");
        assertEquals(FMLMissingMappingsEvent.Action.REMAP, block.getAction());
        assertEquals(FMLMissingMappingsEvent.Action.REMAP, item.getAction());
        assertSame(net.minecraft.init.Blocks.STONE, block.getTarget());
        assertSame(net.minecraft.init.Items.DIAMOND, item.getTarget());
    }

    @Test public void lootMigrationPreservesCustomValuesAndDoesNotActivateTwoCopies() throws Exception {
        Path config = temporary.newFolder().toPath();
        Path old = config.resolve("additional-loot-tables/poweradvantage/chests/custom.json");
        Files.createDirectories(old.getParent());
        byte[] original = "{\"name\":\"poweradvantage:sprocket\",\"weight\":17,\"foreign\":\"basemetals:steel_ingot\"}"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(old, original);
        LegacyIdentity.migrateAdditionalLootTables(config, "poweradvantage", "basepower");
        Path current = config.resolve("additional-loot-tables/basepower/chests/custom.json");
        String migrated = new String(Files.readAllBytes(current), StandardCharsets.UTF_8);
        assertTrue(migrated.contains("basepower:sprocket"));
        assertTrue(migrated.contains("17")); assertTrue(migrated.contains("basemetals:steel_ingot"));
        assertFalse(Files.exists(old));
        assertArrayEquals(original, Files.readAllBytes(old.resolveSibling("custom.json.before-basepower")));
        LegacyIdentity.migrateAdditionalLootTables(config, "poweradvantage", "basepower");
        assertEquals(migrated, new String(Files.readAllBytes(current), StandardCharsets.UTF_8));
    }

    @Test public void onlyExactNamespacePrefixesAreRewritten() {
        assertEquals("basepower:sprocket", LegacyIdentity.remapIdentifier("poweradvantage:sprocket", "poweradvantage", "basepower"));
        assertEquals("otherpoweradvantage:sprocket", LegacyIdentity.remapIdentifier("otherpoweradvantage:sprocket", "poweradvantage", "basepower"));
    }

    @Test public void oldAndNewJarsCannotBeLoadedTogether() {
        LegacyIdentity.requireReplacementsOnly(id -> false);
        for (String name : new String[] { "poweradvantage", "steamadvantage", "electricadvantage" }) {
            try {
                LegacyIdentity.requireReplacementsOnly(name::equals);
                fail("Mixed old/new mod sets must stop before configuration or world migration");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains(name));
                assertTrue(expected.getMessage().contains("Remove"));
            }
        }
    }

    @Test public void steamLootKeepsTheFourPersistedEnchantmentKeysButRenamesItems() {
        for (String name : new String[] { "rapid_reload", "powderless", "high_explosive", "recoil" }) {
            assertEquals("steamadvantage:" + name,
                    LegacyIdentity.remapIdentifier("steamadvantage:" + name, "steamadvantage", "basesteam"));
        }
        assertEquals("basesteam:musket", LegacyIdentity.remapIdentifier("steamadvantage:musket", "steamadvantage", "basesteam"));
    }

    public static class MigrationTile extends TileEntity { }
}
