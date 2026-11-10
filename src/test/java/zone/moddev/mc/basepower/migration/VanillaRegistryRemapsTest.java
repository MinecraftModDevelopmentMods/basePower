package zone.moddev.mc.basepower.migration;

import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import org.junit.Test;
import static org.junit.Assert.*;

public class VanillaRegistryRemapsTest {
    @Test public void retainsBothGrassPathRegistrySnapshots() {
        expect("block", "grass_path", "dirt_path");
        expect("item", "grass_path", "dirt_path");
        assertNull(VanillaRegistryRemaps.replacement(id("entity_type"), id("grass_path")));
    }

    @Test public void retainsVanillaBlockItemAndEnchantmentRenamesThroughForge50() {
        expect("block", "grass", "short_grass");
        expect("item", "grass", "short_grass");
        expect("item", "scute", "turtle_scute");
        expect("enchantment", "sweeping", "sweeping_edge");
        assertNull(VanillaRegistryRemaps.replacement(id("block"), id("scute")));
        assertNull(VanillaRegistryRemaps.replacement(id("item"), id("sweeping")));
    }

    @Test public void retainsTheRenamedBerryPickingSound() {
        expect("sound_event", "item.sweet_berries.pick_from_bush", "block.sweet_berry_bush.pick_berries");
        assertNull(VanillaRegistryRemaps.replacement(id("item"), id("item.sweet_berries.pick_from_bush")));
    }

    @Test public void preservesTheFourPotteryStacksThroughVanillaAndForgeRenames() {
        SharedConstants.tryDetectVersion();
        for (String pattern : new String[]{"archer", "arms_up", "prize", "skull"}) {
            String oldName = "pottery_shard_" + pattern;
            String newName = pattern + "_pottery_sherd";
            expect("item", oldName, newName);
            assertNull(VanillaRegistryRemaps.replacement(id("block"), id(oldName)));
            CompoundTag stack = new CompoundTag();
            stack.putString("id", id(oldName).toString());
            stack.putByte("Count", (byte) 7);
            CompoundTag metadata = new CompoundTag();
            metadata.putInt("WorksWitness", 1194);
            stack.put("tag", metadata);
            var fixed = DataFixers.getDataFixer().update(References.ITEM_STACK,
                    new Dynamic<>(NbtOps.INSTANCE, stack), 3337, 3465);
            assertEquals(id(newName).toString(), fixed.get("id").asString().result().orElseThrow());
            assertEquals(7, fixed.get("Count").asInt(-1));
            assertEquals(1194, fixed.get("tag").get("WorksWitness").asInt(-1));
        }
    }

    @Test public void retainsExactArchaeologyAndMusicSoundRegistryAliases() {
        expect("sound_event", "item.brush.brushing", "item.brush.brushing.generic");
        expect("sound_event", "item.brush.brush_sand_completed", "item.brush.brushing.sand.complete");
        expect("sound_event", "music.overworld.jungle_and_forest", "music.overworld.forest");
        assertNull(VanillaRegistryRemaps.replacement(id("sound_event"), id("item.brush.unknown")));
        assertNull(VanillaRegistryRemaps.replacement(id("item"), id("music.overworld.jungle_and_forest")));
    }
    @Test public void retainsTheRenamedPigmanEntity() {
        expect("entity_type", "zombie_pigman", "zombified_piglin");
    }

    @Test public void retainsTheRenamedPigmanEgg() {
        expect("item", "zombie_pigman_spawn_egg", "zombified_piglin_spawn_egg");
    }

    @Test public void retainsEachEquivalentPigmanSound() {
        for (String name : new String[] {"ambient", "angry", "death", "hurt"})
            expect("sound_event", "entity.zombie_pigman." + name, "entity.zombified_piglin." + name);
    }

    @Test public void retainsTheFormerNetherBiomeMusic() {
        expect("sound_event", "music.nether", "music.nether.nether_wastes");
    }

    @Test public void retainsTheFormerNetherBiome() {
        expect("worldgen/biome", "nether", "nether_wastes");
    }

    @Test public void onlyRetiresBiomeIndexesHandledByTheNativeDataFixer() {
        ResourceLocation biomes = id("worldgen/biome");
        assertEquals(id("desert"), VanillaRegistryRemaps.vanillaBiomeFixTarget(biomes, id("desert_hills")));
        assertEquals(id("desert"), VanillaRegistryRemaps.vanillaBiomeFixTarget(biomes, id("desert_lakes")));
        assertEquals(id("warm_ocean"), VanillaRegistryRemaps.vanillaBiomeFixTarget(biomes, id("deep_warm_ocean")));
        assertNull(VanillaRegistryRemaps.vanillaBiomeFixTarget(biomes, id("plains")));
        assertNull(VanillaRegistryRemaps.vanillaBiomeFixTarget(biomes, id("unknown_biome")));
        assertNull(VanillaRegistryRemaps.vanillaBiomeFixTarget(biomes, new ResourceLocation("example:desert_hills")));
        assertNull(VanillaRegistryRemaps.vanillaBiomeFixTarget(id("block"), id("desert_hills")));
    }

    @Test public void vanillaDataFixerActuallyConvertsTheMergedBiomeNames() {
        SharedConstants.tryDetectVersion();
        assertBiomeFix("desert_hills", "desert");
        assertBiomeFix("desert_lakes", "desert");
        assertBiomeFix("deep_warm_ocean", "warm_ocean");
    }

    private static void assertBiomeFix(String from, String to) {
        var input = new Dynamic<>(JsonOps.INSTANCE, JsonOps.INSTANCE.createString(id(from).toString()));
        var fixed = DataFixers.getDataFixer().update(References.BIOME, input, 2730, 2975);
        assertEquals(id(to).toString(), fixed.asString().result().orElseThrow());
    }

    @Test public void leavesOtherModsAndUnrecognizedVanillaEntriesAlone() {
        assertNull(VanillaRegistryRemaps.replacement(id("item"), new ResourceLocation("othermod:zombie_pigman_spawn_egg")));
        assertNull(VanillaRegistryRemaps.replacement(id("item"), id("unknown_item")));
        assertNull(VanillaRegistryRemaps.replacement(id("sound_event"), id("music.unknown")));
    }

    @Test public void neverAppliesAnAliasToTheWrongRegistry() {
        assertNull(VanillaRegistryRemaps.replacement(id("block"), id("zombie_pigman")));
        assertNull(VanillaRegistryRemaps.replacement(id("item"), id("entity.zombie_pigman.hurt")));
    }

    private static void expect(String registry, String from, String to) {
        assertEquals(id(to), VanillaRegistryRemaps.replacement(id(registry), id(from)));
    }

    private static ResourceLocation id(String name) { return new ResourceLocation("minecraft", name); }
}
