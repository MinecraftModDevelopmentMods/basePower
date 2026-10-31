package zone.moddev.mc.advantageworks;

import java.util.Collections;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.StructureSettings;
import net.minecraftforge.common.world.ForgeWorldType;
import net.minecraftforge.event.RegistryEvent;

final class Native117TestFlat {
    static final String ID = "advantageworkstest:test_flat";

    private Native117TestFlat() {}

    static ForgeWorldType createType() {
        return new ForgeWorldType((biomes, dimensions, seed, settings) -> {
            if (!settings.isEmpty()) throw new IllegalArgumentException("Test flat creation settings must be empty");
            FlatLevelGeneratorSettings flat = new FlatLevelGeneratorSettings(
                    new StructureSettings(Optional.empty(), Collections.emptyMap()), biomes);
            flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.BEDROCK));
            flat.getLayersInfo().add(new FlatLayerInfo(2, Blocks.DIRT));
            flat.getLayersInfo().add(new FlatLayerInfo(1, Blocks.GRASS_BLOCK));
            flat.updateLayers();
            return new FlatLevelSource(flat);
        }).setRegistryName(new ResourceLocation(ID));
    }

    static void register(RegistryEvent.Register<ForgeWorldType> event) {
        event.getRegistry().register(createType());
    }
}
