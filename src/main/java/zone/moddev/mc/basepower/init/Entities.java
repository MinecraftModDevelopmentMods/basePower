package zone.moddev.mc.basepower.init;

import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.datafix.FixTypes;
import net.minecraftforge.common.util.ModFixs;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.registry.GameRegistry;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.compat.LegacyIdentity;
import zone.moddev.mc.basepower.machines.conveyors.*;
import zone.moddev.mc.basepower.machines.creative.InfiniteEnergyTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.*;
import zone.moddev.mc.basepower.machines.fluidmachines.modsupport.TerminalFluidPipeTileEntity;

public abstract class Entities {


	private static boolean initDone = false;
	private static boolean dataFixersRegistered = false;

	public static void registerDataFixers() {
		if (dataFixersRegistered) return;
		ModFixs fixes = FMLCommonHandler.instance().getDataFixer().init(PowerAdvantage.MODID, 1);
		fixes.registerFix(FixTypes.BLOCK_ENTITY, LegacyIdentity.tileIds(PowerAdvantage.LEGACY_MODID, PowerAdvantage.MODID, 1));
		fixes.registerFix(FixTypes.ITEM_INSTANCE, LegacyIdentity.itemIds(PowerAdvantage.LEGACY_MODID, PowerAdvantage.MODID, 1));
		dataFixersRegistered = true;
	}

	public static void init() {
		if (initDone) return;

		Blocks.init();

		registerTileEntity(TerminalFluidPipeTileEntity.class, "tileentity.pipe_terminal");
		registerTileEntity(FluidDrainTileEntity.class, "tileentity.fluid_drain");
		registerTileEntity(FluidDischargeTileEntity.class, "tileentity.fluid_discharge");
		registerTileEntity(StorageTankTileEntity.class, "tileentity.fluid_storage_tank");
		registerTileEntity(MetalTankTileEntity.class, "tileentity.fluid_metal_tank");
		registerTileEntity(StillTileEntity.class, "tileentity.still");
		registerTileEntity(TileEntityConveyor.class, "item_conveyor");
		registerTileEntity(TileEntityBlockFilter.class, "item_filter_block");
		registerTileEntity(TileEntityFoodFilter.class, "item_filter_food");
		registerTileEntity(TileEntityFuelFilter.class, "item_filter_fuel");
		registerTileEntity(TileEntityInventoryFilter.class, "item_filter_inventory");
		registerTileEntity(TileEntityOreFilter.class, "item_filter_ore");
		registerTileEntity(TileEntityPlantFilter.class, "item_filter_plant");
		registerTileEntity(TileEntitySmeltableFilter.class, "item_filter_smelt");
		registerTileEntity(TileEntityOverflowFilter.class, "item_filter_overflow");

		registerTileEntity(InfiniteEnergyTileEntity.class, "infinite_energy_source");

		initDone = true;
	}

	public static void registerTileEntity(Class<? extends TileEntity> tileEntityClass, String path) {
		GameRegistry.registerTileEntity(tileEntityClass, new ResourceLocation(PowerAdvantage.MODID, path));
	}

}
