package zone.moddev.mc.basepower.init;

import java.util.Locale;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.modsupport.rf.BlockRFConverter;
import cyano.poweradvantage.api.modsupport.rf.TileEntityRFElectricityConverter;
import cyano.poweradvantage.api.modsupport.rf.TileEntityRFQuantumConverter;
import cyano.poweradvantage.api.modsupport.rf.TileEntityRFSteamConverter;
import cyano.poweradvantage.api.modsupport.techreborn.BlockTRConverter;
import cyano.poweradvantage.api.modsupport.techreborn.TileEntityTRElectricityConverter;
import net.minecraft.world.level.block.Block;
import cyano.poweradvantage.api.GUIBlock;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.config.PowerAdvantageConfig;

/** Registers the converter blocks whose historical IDs are retained in 1.13. */
public final class ModSupport {
    public static Block converter_rf_steam;
    public static Block converter_rf_electricity;
    public static Block converter_rf_quantum;
    public static Block converter_tr_electricity;

    private static boolean initialized;

    private ModSupport() {}

    public static synchronized void initBlocks() {
        if (initialized) return;
        float hardness = 0.75F;
        converter_rf_steam = Blocks.add(new BlockRFConverter(GUIBlock.machineProperties(hardness), new ConduitType("steam"),
                TileEntityRFSteamConverter.class), "converter_rf_steam");
        converter_rf_electricity = Blocks.add(new BlockRFConverter(GUIBlock.machineProperties(hardness), new ConduitType("electricity"),
                TileEntityRFElectricityConverter.class), "converter_rf_electricity");
        converter_rf_quantum = Blocks.add(new BlockRFConverter(GUIBlock.machineProperties(hardness), new ConduitType("quantum"),
                TileEntityRFQuantumConverter.class), "converter_rf_quantum");
        converter_tr_electricity = Blocks.add(new BlockTRConverter(GUIBlock.machineProperties(hardness), new ConduitType("electricity"),
                TileEntityTRElectricityConverter.class), "converter_tr_electricity");
        initialized = true;
    }

    public static void configureForgeEnergyConversions() {
        PowerAdvantage.forgeEnergyConversionTable.clear();
        PowerAdvantage.techRebornForgeEnergyConversionTable.clear();
        parseConversions(PowerAdvantageConfig.rfConversions(), PowerAdvantage.forgeEnergyConversionTable);
        parseConversions(PowerAdvantageConfig.techRebornConversions(),
                PowerAdvantage.techRebornForgeEnergyConversionTable);
    }

    private static void parseConversions(String descriptor, java.util.Map<ConduitType, Float> destination) {
        for (String entry : descriptor.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0) continue;
            String name = entry.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            try {
                float factor = Float.parseFloat(entry.substring(separator + 1).trim());
                if (factor > 0) destination.put(new ConduitType(name), factor);
            } catch (NumberFormatException exception) {
                PowerAdvantage.LOGGER.warn("Ignoring invalid Forge Energy conversion '{}'", entry);
            }
        }
    }
}
