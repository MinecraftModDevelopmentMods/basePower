package zone.moddev.mc.basepower;

import java.util.HashMap;
import java.util.Map;
import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.migration.LegacyConfigMigration;
import cyano.poweradvantage.api.migration.LegacyConfigMigration.Mapping;
import cyano.poweradvantage.api.migration.LegacyConfigMigration.ValueType;
import cyano.poweradvantage.api.migration.LegacyOreSpawnConfigBridge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.common.NeoForge;

import net.neoforged.fml.ModList;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.FMLPaths;
import zone.moddev.mc.basepower.config.PowerAdvantageConfig;
import zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry;
import zone.moddev.mc.basepower.init.Fluids;
import zone.moddev.mc.basepower.init.GUI;
import zone.moddev.mc.basepower.init.Fuels;
import zone.moddev.mc.basepower.init.ItemGroups;
import zone.moddev.mc.basepower.init.ModSupport;
import zone.moddev.mc.basepower.init.Recipes;
import zone.moddev.mc.basepower.registry.MachineGUIRegistry;
import zone.moddev.mc.basepower.recipe.RecipeConditions;
import zone.moddev.mc.basepower.recipe.RecipeDiscovery;
import zone.moddev.mc.basepower.migration.AdvantageLegacyMigrations;
import zone.moddev.mc.basepower.migration.BaseMetalsLegacyMigration;

/** Forge entry point for basePower. */
@Mod(PowerAdvantage.MODID)
public final class PowerAdvantage {
    public static final String MODID = "basepower";
    public static final String NAME = "basePower";
    public static final String VERSION = "2.4.19.121012";
    public static final Logger LOGGER = LogManager.getLogger(MODID);

    private static PowerAdvantage instance;

    /** Public compatibility settings consumed by Advantage add-ons. */
    public static RecipeMode recipeMode = RecipeMode.NORMAL;
    public static boolean useOtherFluids;
    public static float chestLootFactor = 0.5F;
    public static boolean plasticIsAlsoRubber = true;

    /** All historical RF- and Tech Reborn-labelled converters use Forge Energy in 1.16. */
    public static final Map<ConduitType, Float> forgeEnergyConversionTable = new HashMap<>();

    /** Conversion table for the historical Tech Reborn-labelled Forge Energy converter. */
    public static final Map<ConduitType, Float> techRebornForgeEnergyConversionTable = new HashMap<>();

    /** @deprecated Use {@link #forgeEnergyConversionTable}. */
    @Deprecated
    public static final Map<ConduitType, Float> rfConversionTable = forgeEnergyConversionTable;
    /** @deprecated Use {@link #forgeEnergyConversionTable}. */
    @Deprecated
    public static final Map<ConduitType, Float> trConversionTable = forgeEnergyConversionTable;

    /** Retained for source compatibility; Forge Energy is always available. */
    @Deprecated public static boolean detectedRF = true;
    /** Retained for source compatibility; the labelled converter now uses Forge Energy. */
    @Deprecated public static boolean detectedTechReborn = true;

    public PowerAdvantage(IEventBus modBus, ModContainer context) {
        instance = this;

        AdvantageLegacyMigrations.registerPowerAdvantage();
        modBus.addListener(net.neoforged.bus.api.EventPriority.LOWEST,
                zone.moddev.mc.basepower.migration.VanillaRegistryRemaps::registerAliases);
        NeoForge.EVENT_BUS.addListener(ConduitRegistry.getInstance()::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(ConduitRegistry.getInstance()::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(RecipeDiscovery::onPlayerLogin);
        NeoForge.EVENT_BUS.addListener(zone.moddev.mc.basepower.init.Villages::onTrades);
        modBus.addListener(
                zone.moddev.mc.basepower.recipe.MaterialTags::registerPack);
        modBus.addListener(MachineGUIRegistry::registerTypes);
        modBus.addListener(zone.moddev.mc.basepower.util.MachineCapabilities::register);

        Fluids.init();
        ItemGroups.register(modBus);
        RecipeConditions.register(modBus);
        zone.moddev.mc.basepower.recipe.PartsLoot.register(modBus);

        migrateLegacyOreSpawnConfiguration();
        migrateLegacyConfiguration();
        context.registerConfig(ModConfig.Type.COMMON, PowerAdvantageConfig.SPEC);
        modBus.addListener(PowerAdvantageConfig::onConfigLoading);
        modBus.addListener(this::commonSetup);

        if (net.neoforged.fml.loading.FMLEnvironment.dist == Dist.CLIENT) {
            zone.moddev.mc.basepower.client.ClientRegistration.register(modBus);
        }
    }

    private static void migrateLegacyOreSpawnConfiguration() {
        try {
            LegacyOreSpawnConfigBridge.Result result =
                    LegacyOreSpawnConfigBridge.migrate(FMLPaths.CONFIGDIR.get(), MODID, "poweradvantage");
            if (result.wasMigrated()) {
                LOGGER.info("Imported {} legacy Power Advantage OreSpawn rule(s) from '{}' into '{}'",
                        result.getRuleCount(), result.getSource(), result.getTarget());
            }
        } catch (IOException exception) {
            LOGGER.error("Could not migrate legacy Power Advantage OreSpawn configuration", exception);
        }
    }

    private static void migrateLegacyConfiguration() {
        try {
            LegacyConfigMigration.Result result = LegacyConfigMigration.migrate(FMLPaths.CONFIGDIR.get(), MODID, "poweradvantage",
                    Mapping.of("options", "recipe_mode", "options", "recipeMode", ValueType.STRING),
                    Mapping.of("options", "treasure_chest_loot_factor", "options", "treasureChestLootFactor", ValueType.DOUBLE),
                    Mapping.of("options", "plastic_equals_rubber", "options", "plasticEqualsRubber", ValueType.BOOLEAN),
                    Mapping.of("Other Power Mods", "use_other_fluids", "options", "useOtherFluids", ValueType.BOOLEAN),
                    Mapping.of("recipes", "distiller_recipes", "recipes", "distillerRecipes", ValueType.STRING),
                    Mapping.of("compatibility", "additional_crude_oil_fluid_aliases", "compatibility", "additionalCrudeOilFluidAliases", ValueType.STRING),
                    Mapping.of("Other Power Mods", "RF_conversions", "energy", "rfConversions", ValueType.STRING),
                    Mapping.of("Other Power Mods", "TechReborn_conversions", "energy", "techRebornConversions", ValueType.STRING));
            if (result.wasMigrated()) {
                LOGGER.info("Imported {} legacy Power Advantage settings; report: {}",
                        result.getImportedValues(), result.getReportFile());
            }
        } catch (IOException exception) {
            LOGGER.error("Could not migrate legacy Power Advantage configuration", exception);
        }
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        Fuels.init();
        GUI.init();
        if (ModList.get().isLoaded("basemetals")) {
            event.enqueueWork(() -> BaseMetalsLegacyMigration.register());
        }
        // Forge's shared hook list is not safe to mutate during parallel mod construction.
        PowerAdvantageConfig.apply();
        ModSupport.configureForgeEnergyConversions();
        Recipes.initDistillationRecipes(PowerAdvantageConfig.distillationRecipes());
        LOGGER.info("Power Advantage {} common setup complete", VERSION);
    }

    /** Legacy API retained for add-ons that pass the mod owner to GUIBlock. */
    public static PowerAdvantage getInstance() {
        return instance;
    }
}
