package zone.moddev.mc.basepower.init;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityBlockFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityFoodFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityFuelFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityInventoryFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityOreFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityOverflowFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityPlantFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntitySmeltableFilter;
import zone.moddev.mc.basepower.machines.creative.InfiniteEnergyTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.FluidDischargeTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.FluidDrainTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.MetalTankTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.StillTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.StorageTankTileEntity;
import zone.moddev.mc.basepower.machines.fluidmachines.modsupport.TerminalFluidPipeTileEntity;
import cyano.poweradvantage.api.modsupport.rf.TileEntityRFElectricityConverter;
import cyano.poweradvantage.api.modsupport.rf.TileEntityRFQuantumConverter;
import cyano.poweradvantage.api.modsupport.rf.TileEntityRFSteamConverter;
import cyano.poweradvantage.api.modsupport.techreborn.TileEntityTRElectricityConverter;

/** Stable tile identities with the native 1.14 valid-block lists. */
@Mod.EventBusSubscriber(modid = PowerAdvantage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Entities {
    public static BlockEntityType<TerminalFluidPipeTileEntity> PIPE_TERMINAL;
    public static BlockEntityType<FluidDrainTileEntity> FLUID_DRAIN;
    public static BlockEntityType<FluidDischargeTileEntity> FLUID_DISCHARGE;
    public static BlockEntityType<StorageTankTileEntity> STORAGE_TANK;
    public static BlockEntityType<MetalTankTileEntity> METAL_TANK;
    public static BlockEntityType<StillTileEntity> STILL;
    public static BlockEntityType<TileEntityConveyor> CONVEYOR;
    public static BlockEntityType<TileEntityBlockFilter> FILTER_BLOCK;
    public static BlockEntityType<TileEntityFoodFilter> FILTER_FOOD;
    public static BlockEntityType<TileEntityFuelFilter> FILTER_FUEL;
    public static BlockEntityType<TileEntityInventoryFilter> FILTER_INVENTORY;
    public static BlockEntityType<TileEntityOreFilter> FILTER_ORE;
    public static BlockEntityType<TileEntityPlantFilter> FILTER_PLANT;
    public static BlockEntityType<TileEntitySmeltableFilter> FILTER_SMELT;
    public static BlockEntityType<TileEntityOverflowFilter> FILTER_OVERFLOW;
    public static BlockEntityType<InfiniteEnergyTileEntity> INFINITE_ENERGY;
    public static BlockEntityType<TileEntityRFSteamConverter> RF_STEAM_CONVERTER;
    public static BlockEntityType<TileEntityRFElectricityConverter> RF_ELECTRICITY_CONVERTER;
    public static BlockEntityType<TileEntityRFQuantumConverter> RF_QUANTUM_CONVERTER;
    public static BlockEntityType<TileEntityTRElectricityConverter> TR_ELECTRICITY_CONVERTER;

    private Entities() {}

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        if (!ForgeRegistries.Keys.BLOCK_ENTITY_TYPES.equals(event.getRegistryKey())) return;
        PIPE_TERMINAL = register(event, "tileentity.pipe_terminal", TerminalFluidPipeTileEntity::new, Blocks.fluid_pipe_terminal);
        FLUID_DRAIN = register(event, "tileentity.fluid_drain", FluidDrainTileEntity::new, Blocks.fluid_drain);
        FLUID_DISCHARGE = register(event, "tileentity.fluid_discharge", FluidDischargeTileEntity::new, Blocks.fluid_discharge);
        STORAGE_TANK = register(event, "tileentity.fluid_storage_tank", StorageTankTileEntity::new, Blocks.storage_tank);
        METAL_TANK = register(event, "tileentity.fluid_metal_tank", MetalTankTileEntity::new, Blocks.metal_storage_tank);
        STILL = register(event, "tileentity.still", StillTileEntity::new, Blocks.still);
        CONVEYOR = register(event, "item_conveyor", TileEntityConveyor::new, Blocks.item_conveyor);
        FILTER_BLOCK = register(event, "item_filter_block", TileEntityBlockFilter::new, Blocks.item_filter_block);
        FILTER_FOOD = register(event, "item_filter_food", TileEntityFoodFilter::new, Blocks.item_filter_food);
        FILTER_FUEL = register(event, "item_filter_fuel", TileEntityFuelFilter::new, Blocks.item_filter_fuel);
        FILTER_INVENTORY = register(event, "item_filter_inventory", TileEntityInventoryFilter::new, Blocks.item_filter_inventory);
        FILTER_ORE = register(event, "item_filter_ore", TileEntityOreFilter::new, Blocks.item_filter_ore);
        FILTER_PLANT = register(event, "item_filter_plant", TileEntityPlantFilter::new, Blocks.item_filter_plant);
        FILTER_SMELT = register(event, "item_filter_smelt", TileEntitySmeltableFilter::new, Blocks.item_filter_smelt);
        FILTER_OVERFLOW = register(event, "item_filter_overflow", TileEntityOverflowFilter::new, Blocks.item_filter_overflow);
        INFINITE_ENERGY = register(event, "infinite_energy_source", InfiniteEnergyTileEntity::new,
                Blocks.infinite_steam, Blocks.infinite_electricity, Blocks.infinite_quantum);
        RF_STEAM_CONVERTER = register(event, "rf_steam_converter_tileentity", TileEntityRFSteamConverter::new, ModSupport.converter_rf_steam);
        RF_ELECTRICITY_CONVERTER = register(event, "rf_electricity_converter_tileentity", TileEntityRFElectricityConverter::new, ModSupport.converter_rf_electricity);
        RF_QUANTUM_CONVERTER = register(event, "rf_quantum_converter_tileentity", TileEntityRFQuantumConverter::new, ModSupport.converter_rf_quantum);
        TR_ELECTRICITY_CONVERTER = register(event, "tr_electricity_converter_tileentity", TileEntityTRElectricityConverter::new, ModSupport.converter_tr_electricity);
    }

    private static <T extends BlockEntity> BlockEntityType<T> register(
            RegisterEvent event, String id,
            BlockEntityType.BlockEntitySupplier<T> factory, net.minecraft.world.level.block.Block... validBlocks) {
        BlockEntityType<T> type = BlockEntityType.Builder.<T>of(factory, validBlocks).build(null);
        event.register(ForgeRegistries.Keys.BLOCK_ENTITY_TYPES, ResourceLocation.fromNamespaceAndPath(PowerAdvantage.MODID, id), () -> type);
        return type;
    }
}
