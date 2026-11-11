package zone.moddev.mc.basepower.migration;

import java.nio.file.Files;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DataDrivenRegistryMigrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void archivesOnlyTheRetiredFlattenedIndexWithoutEditingAnyEnchantments() throws Exception {
        var root = TagParser.parseTag("{Data:{DataVersion:3839,Player:{Inventory:[{id:\"basesteam:musket\",count:1,"
                + "components:{\"minecraft:enchantments\":{levels:{\"basesteam:recoil\":3}}}}]}},"
                + "fml:{Registries:{\"minecraft:enchantment\":{ids:[{K:\"basesteam:recoil\",V:101},"
                + "{K:\"other:private_enchantment\",V:102}],blocked:[I;105]},\"other:registry\":{opaque:1}}},"
                + "FML:{Registries:{\"minecraft:enchantments\":{ids:[{K:\"steamadvantage:recoil\",V:77}]}}}}");
        var old = root.copy();
        assertTrue(LegacyWorldDataHook.archiveDataDrivenEnchantmentIndex(root));
        assertEquals(old.getCompound("Data"), root.getCompound("Data"));
        assertEquals(old.getCompound("FML"), root.getCompound("FML"));
        assertEquals(old.getCompound("fml").getCompound("Registries").get("other:registry"),
                root.getCompound("fml").getCompound("Registries").get("other:registry"));
        assertEquals(old.getCompound("fml").getCompound("Registries").get("minecraft:enchantment"),
                root.getCompound("basepower:legacy_registry_indexes").get("minecraft:enchantment"));
        assertFalse(root.getCompound("fml").getCompound("Registries").contains("minecraft:enchantment"));
        var once = root.copy();
        assertFalse(LegacyWorldDataHook.archiveDataDrivenEnchantmentIndex(root));
        assertEquals(once, root);
    }

    @Test public void filePreparationKeepsAnExactArchiveAcrossAnotherSaveAndRestart() throws Exception {
        var world = temporary.newFolder().toPath();
        var file = world.resolve("level.dat");
        var source = TagParser.parseTag("{Data:{DataVersion:3839},fml:{Registries:"
                + "{\"minecraft:enchantment\":{ids:[{K:\"basesteam:recoil\",V:101}]}}}}");
        NbtIo.writeCompressed(source, file);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        assertEquals(source, NbtIo.readCompressed(world.resolve("level.dat.basepower-legacy-backup"),
                NbtAccounter.unlimitedHeap()));
        var archive = world.resolve("basepower-retired-registry-indexes.dat");
        byte[] bytes = Files.readAllBytes(archive);
        assertEquals(source.getCompound("fml").getCompound("Registries"),
                NbtIo.readCompressed(archive, NbtAccounter.unlimitedHeap()));
        var current = TagParser.parseTag("{Data:{DataVersion:3955},fml:{Registries:{}}}");
        NbtIo.writeCompressed(current, file);
        LegacyWorldDataHook.prepareForgeRegistryMetadata(file.toFile());
        assertArrayEquals(bytes, Files.readAllBytes(archive));
        assertEquals(current, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()));
    }

    @Test public void recognizedPaintingIndexesStillResolveInTheNativeDataRegistry() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup()
                .lookupOrThrow(net.minecraft.core.registries.Registries.PAINTING_VARIANT);
        var registry = ResourceLocation.parse("minecraft:painting_variant");
        int found = 0;
        for (var holder : lookup.listElements().toList()) {
            if (VanillaRegistryRemaps.retiredPaintingIndex(registry, holder.key().location())) found++;
        }
        assertEquals(30, found);
        assertFalse(VanillaRegistryRemaps.retiredPaintingIndex(registry, ResourceLocation.parse("other:alban")));
        assertFalse(VanillaRegistryRemaps.retiredPaintingIndex(registry, ResourceLocation.parse("minecraft:unknown")));
        assertFalse(VanillaRegistryRemaps.retiredPaintingIndex(ResourceLocation.parse("minecraft:item"),
                ResourceLocation.parse("minecraft:alban")));
    }

    @Test public void aConflictingArchiveIsNeverReplacedOrSilentlyDiscarded() throws Exception {
        var root = TagParser.parseTag("{fml:{Registries:{\"minecraft:enchantment\":{ids:[{K:\"basesteam:recoil\",V:3}]}}},"
                + "\"basepower:legacy_registry_indexes\":{\"minecraft:enchantment\":{ids:[{K:\"other:different\",V:4}]}}}");
        var before = root.copy();
        try {
            LegacyWorldDataHook.archiveDataDrivenEnchantmentIndex(root);
            fail("Conflicting archive was accepted");
        } catch (IllegalStateException expected) {
            assertEquals(before, root);
        }
    }

    @Test public void nativePaintingLoadStillKeepsTheSavedVariantFacingAndUuid() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerVanillaFluidTypes();
        var paintings = new net.minecraft.core.MappedRegistry<>(
                net.minecraft.core.registries.Registries.PAINTING_VARIANT, com.mojang.serialization.Lifecycle.stable());
        net.minecraft.data.registries.VanillaRegistries.createLookup()
                .lookupOrThrow(net.minecraft.core.registries.Registries.PAINTING_VARIANT).listElements().forEach(holder ->
                        net.minecraft.core.Registry.register(paintings, holder.key(), holder.value()));
        paintings.freeze();
        var world = NativeWolfTestWorld.create();
        var field = net.minecraft.world.level.Level.class.getDeclaredField("registryAccess");
        field.setAccessible(true);
        field.set(world, new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(java.util.List.of(paintings)).freeze());
        var tag = TagParser.parseTag("{id:\"minecraft:painting\",UUID:[I;1,2,3,4],variant:\"minecraft:alban\","
                + "facing:1b,Pos:[10d,80d,12d],Motion:[0d,0d,0d],Rotation:[0f,0f],TileX:10,TileY:80,TileZ:12}");
        var root = new CompoundTag();
        root.putInt("DataVersion", 3839);
        var entities = new net.minecraft.nbt.ListTag();
        entities.add(tag);
        root.put("Entities", entities);
        LegacyEntityDataFixes.prepare(root);
        LegacyEntityDataFixes.finish(root);
        var converted = root.getList("Entities", 10).getCompound(0);
        var entity = new net.minecraft.world.entity.decoration.Painting(net.minecraft.world.entity.EntityType.PAINTING, world);
        entity.load(converted);
        var saved = entity.saveWithoutId(new CompoundTag());
        assertEquals(ResourceLocation.parse("minecraft:alban"), entity.getVariant().unwrapKey().orElseThrow().location());
        assertEquals("minecraft:alban", saved.getString("variant"));
        assertEquals(1, saved.getByte("facing"));
        assertEquals(tag.getUUID("UUID"), entity.getUUID());
    }
}
