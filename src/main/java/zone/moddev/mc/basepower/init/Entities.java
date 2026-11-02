package zone.moddev.mc.basepower.init;

import net.minecraftforge.fml.common.registry.GameRegistry;
import zone.moddev.mc.basepower.compat.LegacyIdentity;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.machines.conveyors.*;
import zone.moddev.mc.basepower.machines.creative.InfiniteEnergyTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.*;
import zone.moddev.mc.basepower.machines.fluidmachines.modsupport.TerminalFluidPipeTileEntity;

public abstract class Entities {


	private static boolean initDone = false;

	public static void init() {
		if (initDone) return;

		Blocks.init();

		LegacyIdentity.registerTileEntity(TerminalFluidPipeTileEntity.class, PowerAdvantage.MODID + ".tileentity." + "pipe_terminal");
		LegacyIdentity.registerTileEntity(FluidDrainTileEntity.class, PowerAdvantage.MODID + ".tileentity." + "fluid_drain");
		LegacyIdentity.registerTileEntity(FluidDischargeTileEntity.class, PowerAdvantage.MODID + ".tileentity." + "fluid_discharge");
		LegacyIdentity.registerTileEntity(StorageTankTileEntity.class, PowerAdvantage.MODID + ".tileentity." + "fluid_storage_tank");
		LegacyIdentity.registerTileEntity(MetalTankTileEntity.class, PowerAdvantage.MODID + ".tileentity." + "fluid_metal_tank");
		LegacyIdentity.registerTileEntity(StillTileEntity.class, PowerAdvantage.MODID + ".tileentity." + "still");
		LegacyIdentity.registerTileEntity(TileEntityConveyor.class, PowerAdvantage.MODID + "." + "item_conveyor");
		LegacyIdentity.registerTileEntity(TileEntityBlockFilter.class, PowerAdvantage.MODID + "." + "item_filter_block");
		LegacyIdentity.registerTileEntity(TileEntityFoodFilter.class, PowerAdvantage.MODID + "." + "item_filter_food");
		LegacyIdentity.registerTileEntity(TileEntityFuelFilter.class, PowerAdvantage.MODID + "." + "item_filter_fuel");
		LegacyIdentity.registerTileEntity(TileEntityInventoryFilter.class, PowerAdvantage.MODID + "." + "item_filter_inventory");
		LegacyIdentity.registerTileEntity(TileEntityOreFilter.class, PowerAdvantage.MODID + "." + "item_filter_ore");
		LegacyIdentity.registerTileEntity(TileEntityPlantFilter.class, PowerAdvantage.MODID + "." + "item_filter_plant");
		LegacyIdentity.registerTileEntity(TileEntitySmeltableFilter.class, PowerAdvantage.MODID + "." + "item_filter_smelt");
		LegacyIdentity.registerTileEntity(TileEntityOverflowFilter.class, PowerAdvantage.MODID + "." + "item_filter_overflow");

		LegacyIdentity.registerTileEntity(InfiniteEnergyTileEntity.class, PowerAdvantage.MODID + "." + "infinite_energy_source");

		initDone = true;
	}
}