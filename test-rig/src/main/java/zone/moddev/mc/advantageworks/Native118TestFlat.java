package zone.moddev.mc.advantageworks;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegistryEvent;

final class Native118TestFlat {
    static final String ID = "advantageworkstest:test_flat";

    private Native118TestFlat() {}

    static ForgeWorldPreset createType() {
        return new ForgeWorldPreset((registries, seed, settings) -> {
            // Forge 40 passes the level-type identifier rather than generator JSON.
            if (!settings.isEmpty() && !settings.equals(ID)) {
                throw new IllegalArgumentException("Unexpected settings for the test flat preset");
            }
            Optional<HolderSet<StructureSet>> structures = Optional.of(HolderSet.direct(List.of()));
            var flat = new FlatLevelGeneratorSettings(structures, registries.registryOrThrow(Registry.BIOME_REGISTRY));
            flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
            flat.getLayersInfo().add(new FlatLayerInfo(2, Blocks.DIRT));
            flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.GRASS_BLOCK));
            flat.updateLayers();
            return new FlatLevelSource(registries.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY), flat);
        }).setRegistryName(new ResourceLocation(ID));
    }

    static void register(RegistryEvent.Register<ForgeWorldPreset> event) {
        event.getRegistry().register(createType());
    }
}
