package zone.moddev.mc.basepower.migration;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import cyano.poweradvantage.api.simple.BlockSimplePowerMachine;
import net.minecraft.block.Block;
import net.minecraft.block.BlockFlowingFluid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.ResourceLocation;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.blocks.BlockPowerSwitch;
import zone.moddev.mc.basepower.machines.conveyors.BlockConveyor;
import zone.moddev.mc.basepower.machines.conveyors.BlockConveyorFilter;
import zone.moddev.mc.basepower.machines.fluidmachines.StillBlock;

/** Registers PowerAdvantage's 1.10 and 1.12 identities with the shared hook. */
public final class AdvantageLegacyMigrations {
    private static boolean registered;

    private AdvantageLegacyMigrations() {}

    public static synchronized void registerPowerAdvantage() {
        if (registered) return;
        LegacyMigrationApi.registerNamespaceAlias("poweradvantage", PowerAdvantage.MODID);

        for (String path : new String[] {
                "fluid_drain", "fluid_discharge", "fluid_storage_tank", "fluid_metal_tank", "still",
                "fluid_pipe", "fluid_pipe_terminal", "steel_frame",
                "item_conveyor", "item_filter_block", "item_filter_food", "item_filter_fuel",
                "item_filter_inventory", "item_filter_ore", "item_filter_plant", "item_filter_smelt",
                "item_filter_overflow", "fluid_switch", "infinite_steam", "infinite_electricity",
                "infinite_quantum", "crude_oil", "refined_oil",
                "converter_rf_steam", "converter_rf_electricity", "converter_rf_quantum",
                "converter_tr_electricity" }) {
            LegacyMigrationApi.registerBlockStateCodec(id(path), AdvantageLegacyMigrations::decodeState);
        }

        registerTile("tileentity.pipe_terminal");
        registerTile("tileentity.fluid_drain");
        registerTile("tileentity.fluid_discharge");
        registerTile("tileentity.fluid_storage_tank");
        registerTile("tileentity.fluid_metal_tank");
        registerTile("tileentity.still");
        registerTile("item_conveyor");
        registerTile("item_filter_block");
        registerTile("item_filter_food");
        registerTile("item_filter_fuel");
        registerTile("item_filter_inventory");
        registerTile("item_filter_ore");
        registerTile("item_filter_plant");
        registerTile("item_filter_smelt");
        registerTile("item_filter_overflow");
        registerTile("infinite_energy_source");
        registerTile("rf_steam_converter_tileentity");
        registerTile("rf_electricity_converter_tileentity");
        registerTile("rf_quantum_converter_tileentity");
        registerTile("tr_electricity_converter_tileentity");

        LegacyMigrationApi.registerFluidAlias("crude_oil", "basepower:crude_oil");
        LegacyMigrationApi.registerFluidAlias("refined_oil", "basepower:refined_oil");
        LegacyMigrationApi.registerFluidAlias("poweradvantage:crude_oil", "basepower:crude_oil");
        LegacyMigrationApi.registerFluidAlias("poweradvantage:refined_oil", "basepower:refined_oil");
        LegacyMigrationApi.registerFluidAlias("poweradvantage:flowing_crude_oil", "basepower:flowing_crude_oil");
        LegacyMigrationApi.registerFluidAlias("poweradvantage:flowing_refined_oil", "basepower:flowing_refined_oil");
        LegacyMigrationApi.registerFluidAlias("mineralogy_crude_oil", "mineralogy:crude_oil");
        LegacyMigrationApi.registerLegacyFluidBucket("crude_oil", id("crude_oil_bucket"));
        LegacyMigrationApi.registerLegacyFluidBucket("basepower:crude_oil", id("crude_oil_bucket"));
        LegacyMigrationApi.registerLegacyFluidBucket("refined_oil", id("refined_oil_bucket"));
        LegacyMigrationApi.registerLegacyFluidBucket("basepower:refined_oil", id("refined_oil_bucket"));
        for (String namespace : new String[] {"poweradvantage", PowerAdvantage.MODID}) {
            for (String fluid : new String[] {"crude_oil", "refined_oil"}) {
                LegacyMigrationApi.registerItemAlias(new ResourceLocation(namespace, fluid), id(fluid + "_bucket"));
            }
        }
        registered = true;
    }

    private static void registerTile(String path) {
        LegacyMigrationApi.registerTileEntityAliases(id(path));
    }

    private static IBlockState decodeState(Block block, int metadata) {
        if (block instanceof StillBlock) return ((StillBlock) block).getStateFromMeta(metadata);
        if (block instanceof BlockConveyor) return ((BlockConveyor) block).getStateFromMeta(metadata);
        if (block instanceof BlockConveyorFilter) return ((BlockConveyorFilter) block).getStateFromMeta(metadata);
        if (block instanceof BlockPowerSwitch) return ((BlockPowerSwitch) block).getStateFromMeta(metadata);
        if (block instanceof BlockSimplePowerMachine) {
            return ((BlockSimplePowerMachine) block).getStateFromMeta(metadata);
        }
        if (block instanceof BlockFlowingFluid) {
            return block.getDefaultState().with(BlockFlowingFluid.LEVEL, Integer.valueOf(metadata & 15));
        }
        return block.getDefaultState();
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(PowerAdvantage.MODID, path);
    }
}
