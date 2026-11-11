package zone.moddev.mc.basepower.init;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.GUIBlock;
import cyano.poweradvantage.api.fluid.InteractiveFluidBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.blocks.BlockFrame;
import zone.moddev.mc.basepower.blocks.BlockPowerSwitch;
import zone.moddev.mc.basepower.machines.conveyors.BlockConveyor;
import zone.moddev.mc.basepower.machines.conveyors.BlockConveyorFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityBlockFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityFoodFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityFuelFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityInventoryFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityOreFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityOverflowFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityPlantFilter;
import zone.moddev.mc.basepower.machines.conveyors.TileEntitySmeltableFilter;
import zone.moddev.mc.basepower.machines.creative.InfiniteEnergyBlock;
import zone.moddev.mc.basepower.machines.fluidmachines.FluidDischargeBlock;
import zone.moddev.mc.basepower.machines.fluidmachines.FluidDrainBlock;
import zone.moddev.mc.basepower.machines.fluidmachines.FluidPipeBlock;
import zone.moddev.mc.basepower.machines.fluidmachines.MetalTankBlock;
import zone.moddev.mc.basepower.machines.fluidmachines.StillBlock;
import zone.moddev.mc.basepower.machines.fluidmachines.StorageTankBlock;
import zone.moddev.mc.basepower.machines.fluidmachines.modsupport.TerminalFluidPipeBlock;

/** Stable block and block-item registry for the 1.13 port. */
@Mod.EventBusSubscriber(modid = PowerAdvantage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Blocks {
    private static final float STEEL_BLAST_RESISTANCE = 37.5f;
    private static final Map<String, Block> ALL_BLOCKS = new LinkedHashMap<>();

    public static GUIBlock fluid_drain;
    public static GUIBlock fluid_discharge;
    public static GUIBlock storage_tank;
    public static GUIBlock metal_storage_tank;
    public static GUIBlock still;
    public static Block fluid_pipe;
    public static Block fluid_pipe_terminal;
    public static Block fluid_switch;
    public static GUIBlock item_conveyor;
    public static GUIBlock item_filter_block;
    public static GUIBlock item_filter_food;
    public static GUIBlock item_filter_fuel;
    public static GUIBlock item_filter_inventory;
    public static GUIBlock item_filter_ore;
    public static GUIBlock item_filter_plant;
    public static GUIBlock item_filter_smelt;
    public static GUIBlock item_filter_overflow;
    public static Block steel_frame;
    public static GUIBlock infinite_steam;
    public static GUIBlock infinite_electricity;
    public static GUIBlock infinite_quantum;
    public static LiquidBlock crude_oil_block;
    public static LiquidBlock refined_oil_block;

    private static boolean initialized;

    private Blocks() {}

    public static synchronized void init() {
        if (initialized) return;
        Fluids.init();

        fluid_drain = (GUIBlock) add(new FluidDrainBlock(), "fluid_drain");
        fluid_discharge = (GUIBlock) add(new FluidDischargeBlock(), "fluid_discharge");
        storage_tank = (GUIBlock) add(new StorageTankBlock(), "fluid_storage_tank");
        metal_storage_tank = (GUIBlock) add(new MetalTankBlock(), "fluid_metal_tank");
        still = (GUIBlock) add(new StillBlock(), "still");
        fluid_pipe = add(new FluidPipeBlock(), "fluid_pipe");
        fluid_pipe_terminal = add(new TerminalFluidPipeBlock(), "fluid_pipe_terminal");
        fluid_switch = add(new BlockPowerSwitch(Fluids.fluidConduit_general), "fluid_switch");
        steel_frame = add(new BlockFrame(GUIBlock.machineProperties(0.75F)
                .strength(0.75F, STEEL_BLAST_RESISTANCE)), "steel_frame");

        final float hardness = 0.75F;
        item_conveyor = (GUIBlock) add(new BlockConveyor(hardness), "item_conveyor");
        item_filter_block = filter("item_filter_block", TileEntityBlockFilter.class, hardness);
        item_filter_food = filter("item_filter_food", TileEntityFoodFilter.class, hardness);
        item_filter_fuel = filter("item_filter_fuel", TileEntityFuelFilter.class, hardness);
        item_filter_inventory = filter("item_filter_inventory", TileEntityInventoryFilter.class, hardness);
        item_filter_ore = filter("item_filter_ore", TileEntityOreFilter.class, hardness);
        item_filter_plant = filter("item_filter_plant", TileEntityPlantFilter.class, hardness);
        item_filter_smelt = filter("item_filter_smelt", TileEntitySmeltableFilter.class, hardness);
        item_filter_overflow = filter("item_filter_overflow", TileEntityOverflowFilter.class, hardness);

        infinite_steam = (GUIBlock) add(new InfiniteEnergyBlock(new ConduitType("steam")), "infinite_steam");
        infinite_electricity = (GUIBlock) add(new InfiniteEnergyBlock(new ConduitType("electricity")), "infinite_electricity");
        infinite_quantum = (GUIBlock) add(new InfiniteEnergyBlock(new ConduitType("quantum")), "infinite_quantum");

        refined_oil_block = (LiquidBlock) add(new InteractiveFluidBlock(() -> Fluids.refined_oil,
                oilProperties(), true, (world, entity) -> entity.addEffect(new MobEffectInstance(MobEffects.CONFUSION, 200))),
                "refined_oil");
        crude_oil_block = (LiquidBlock) add(new InteractiveFluidBlock(() -> Fluids.crude_oil,
                oilProperties(), true, (world, entity) -> entity.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 200, 2))),
                "crude_oil");

        ModSupport.initBlocks();
        initialized = true;
    }

    private static GUIBlock filter(String id, Class<? extends zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor> type,
            float hardness) {
        return (GUIBlock) add(new BlockConveyorFilter(hardness, type), id);
    }

    private static Block.Properties oilProperties() {
        return Block.Properties.of().mapColor(MapColor.WATER).replaceable().liquid().noCollission()
                .strength(100.0F).dynamicShape().pushReaction(PushReaction.DESTROY).noLootTable().sound(SoundType.EMPTY);
    }

    public static Block add(Block block, String id) {
        ALL_BLOCKS.put(id, block);
        return block;
    }

    @SubscribeEvent
    public static void registerBlocks(RegisterEvent event) {
        if (!ForgeRegistries.Keys.BLOCKS.equals(event.getRegistryKey())) return;
        init();
        event.register(ForgeRegistries.Keys.BLOCKS, helper -> ALL_BLOCKS.forEach((id, block) ->
                helper.register(ResourceLocation.fromNamespaceAndPath(PowerAdvantage.MODID, id), block)));
    }

    @SubscribeEvent
    public static void registerBlockItems(RegisterEvent event) {
        if (!ForgeRegistries.Keys.ITEMS.equals(event.getRegistryKey())) return;
        init();
        event.register(ForgeRegistries.Keys.ITEMS, helper -> ALL_BLOCKS.forEach((id, block) -> {
            BlockItem item = new BlockItem(block, new Item.Properties());
            if (!"fluid_pipe_terminal".equals(id) && !(block instanceof LiquidBlock)) {
                ItemGroups.show(item);
            }
            helper.register(ResourceLocation.fromNamespaceAndPath(PowerAdvantage.MODID, id), item);
        }));
    }

    public static Map<String, Block> getModBlockRegistry() {
        return Collections.unmodifiableMap(ALL_BLOCKS);
    }
}
