package zone.moddev.mc.basepower.init;

import zone.moddev.mc.basepower.PowerAdvantage;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.Loader;
import zone.moddev.mc.orespawn.api.OreSpawnApi;
import zone.moddev.mc.orespawn.api.WorldgenProvider;

import java.nio.file.Files;
import java.nio.file.Path;

/** Registers PowerAdvantage's OreSpawn 4 world-generation contribution. */
public final class OreSpawnWorldGen {
    public static final ResourceLocation DESERT_CRUDE_OIL =
            new ResourceLocation(PowerAdvantage.MODID, "fluid_deposit/desert_crude_oil");
    private static final ResourceLocation CRUDE_OIL_BLOCK =
            new ResourceLocation(PowerAdvantage.MODID, "crude_oil");
    private static final ResourceLocation OVERWORLD = new ResourceLocation("minecraft", "overworld");
    private static final ResourceLocation STONE_TAG = new ResourceLocation("forge", "stone");

    private OreSpawnWorldGen() {
    }

    public static void register(Path configDirectory) {
        WorldgenProvider provider = createProvider();
        boolean accepted = OreSpawnApi.enqueue(provider);
        String override = describeOverride(configDirectory);
        String defaults = Loader.isModLoaded("mineralogy")
                ? "mineralogy:ocean crude oil + basepower:desert crude oil"
                : "basepower:desert crude oil";
        FMLLog.info("%s: OreSpawn crude-oil candidates=[mineralogy:ocean, basepower:desert], "
                        + "selected fresh-world defaults=[%s], explicit override=%s",
                PowerAdvantage.MODID, defaults, override);
        if (!accepted) {
            FMLLog.warning("%s: OreSpawn rejected the typed world-generation provider; "
                    + "an authoritative provider override may already be active", PowerAdvantage.MODID);
        }
    }

    public static WorldgenProvider createProvider() {
        return WorldgenProvider.builder(PowerAdvantage.MODID, 1)
                .fluidDeposit(DESERT_CRUDE_OIL, CRUDE_OIL_BLOCK, deposit -> deposit
                        .enabled(true)
                        .dimension(OVERWORLD, dimension -> dimension
                                .enabled(true)
                                .yRange(0, 48)
                                .attempts(0.08D)
                                .radius(5, 12)
                                .verticalRadius(2, 5)
                                .maxLobes(4)
                                .minSolidCover(2)
                                .minSolidShell(1)
                                .hostBlock(new ResourceLocation("minecraft", "stone"))
                                .hostTag(STONE_TAG)
                                .biomeDictionary("SANDY")
                                .excludeBiomeDictionary("BEACH")
                                .excludeBiomeDictionary("MESA")))
                .build();
    }

    static String describeOverride(Path configDirectory) {
        if (Files.isRegularFile(configDirectory.resolve(PowerAdvantage.MODID + "-orespawn.json"))) {
            return "config/" + PowerAdvantage.MODID + "-orespawn.json (authoritative)";
        }
        if (Files.isRegularFile(configDirectory.resolve("orespawn").resolve(PowerAdvantage.MODID + ".json"))) {
            return "config/orespawn/" + PowerAdvantage.MODID + ".json (legacy importer authority)";
        }
        return "none";
    }
}
