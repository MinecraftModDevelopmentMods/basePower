package zone.moddev.mc.basepower.machines.fluidmachines;

import java.util.List;

import javax.annotation.Nullable;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.FluidTank;
import cyano.poweradvantage.api.simple.BlockSimpleFluidMachine;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import zone.moddev.mc.basepower.init.Fluids;
import zone.moddev.mc.basepower.util.ItemData;

/** Portable tank block. FluidID and Volume remain unchanged for old item stacks. */
public class StorageTankBlock extends BlockSimpleFluidMachine {
    public StorageTankBlock() { super(zone.moddev.mc.basepower.init.Blocks.properties("fluid_storage_tank", machineProperties(3F))); }

    @Override public PoweredEntity newBlockEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { return new StorageTankTileEntity(pos, state); }
    @Override public boolean hasAnalogOutputSignal(BlockState state) { return true; }
    @Override public int getAnalogOutputSignal(BlockState state, Level world, BlockPos pos) {
        BlockEntity tile = world.getBlockEntity(pos);
        return tile instanceof StorageTankTileEntity ? ((StorageTankTileEntity) tile).getRedstoneOutput() : 0;
    }
    @Override public boolean isPowerSink(ConduitType type) { return true; }
    @Override public boolean isPowerSource(ConduitType type) { return true; }

    @Override
    public void setPlacedBy(Level world, BlockPos pos, BlockState state,
            @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(world, pos, state, placer, stack);
        CompoundTag tag = ItemData.customData(stack);
        BlockEntity tile = world.getBlockEntity(pos);
        if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(tag, "FluidID", 8) && zone.moddev.mc.basepower.util.NbtCompat.containsTag(tag, "Volume", 99)
                && tile instanceof StorageTankTileEntity) {
            net.minecraft.world.level.material.Fluid fluid = Fluids.byName(tag.getStringOr("FluidID", ""));
            if (fluid != null) ((StorageTankTileEntity) tile).getTank()
                    .setFluid(new FluidStack(fluid, tag.getIntOr("Volume", 0)));
        }
    }

    public ItemStack createItem(StorageTankTileEntity tile) {
        ItemStack stack = new ItemStack(this);
        if (tile.getTank().getFluidAmount() > 0) {
            CompoundTag tag = new CompoundTag();
            net.minecraft.resources.Identifier id = net.minecraftforge.registries.ForgeRegistries.FLUIDS
                    .getKey(tile.getTank().getFluid().getFluid());
            if (id != null) tag.putString("FluidID", id.toString());
            tag.putInt("Volume", tile.getTank().getFluidAmount());
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
        return stack;
    }

    public void appendHoverText(ItemStack stack, Item.TooltipContext context,
            net.minecraft.world.item.component.TooltipDisplay display,
            java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = ItemData.customData(stack);
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(tag, "FluidID", 8) || !zone.moddev.mc.basepower.util.NbtCompat.containsTag(tag, "Volume", 99)) {
            tooltip.accept(Component.translatable("tooltip.basepower.empty"));
            return;
        }
        float buckets = tag.getIntOr("Volume", 0) / (float) FluidTank.BUCKET_VOLUME;
        tooltip.accept(Component.literal(buckets + " buckets of " + tag.getStringOr("FluidID", "")));
    }

    /** Retained for callers from the 1.12 implementation. */
    public void displayInformation(ItemStack stack) {}
}
