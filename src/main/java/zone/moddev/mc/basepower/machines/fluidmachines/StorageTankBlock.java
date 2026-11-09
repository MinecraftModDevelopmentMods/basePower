package zone.moddev.mc.basepower.machines.fluidmachines;

import java.util.List;

import javax.annotation.Nullable;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.FluidTank;
import cyano.poweradvantage.api.simple.BlockSimpleFluidMachine;
import net.minecraft.world.level.material.Material;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import zone.moddev.mc.basepower.init.Fluids;

/** Portable tank block. FluidID and Volume remain unchanged for old item stacks. */
public class StorageTankBlock extends BlockSimpleFluidMachine {
    public StorageTankBlock() { super(Material.PISTON, 3F); }

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
        CompoundTag tag = stack.getTag();
        BlockEntity tile = world.getBlockEntity(pos);
        if (tag != null && tag.contains("FluidID", 8) && tag.contains("Volume", 99)
                && tile instanceof StorageTankTileEntity) {
            net.minecraft.world.level.material.Fluid fluid = Fluids.byName(tag.getString("FluidID"));
            if (fluid != null) ((StorageTankTileEntity) tile).getTank()
                    .setFluid(new FluidStack(fluid, tag.getInt("Volume")));
        }
    }

    public ItemStack createItem(StorageTankTileEntity tile) {
        ItemStack stack = new ItemStack(this);
        if (tile.getTank().getFluidAmount() > 0) {
            CompoundTag tag = new CompoundTag();
            net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.FLUIDS
                    .getKey(tile.getTank().getFluid().getFluid());
            if (id != null) tag.putString("FluidID", id.toString());
            tag.putInt("Volume", tile.getTank().getFluidAmount());
            stack.setTag(tag);
        }
        return stack;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable BlockGetter world,
            List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("FluidID", 8) || !tag.contains("Volume", 99)) {
            tooltip.add(Component.translatable("tooltip.basepower.empty"));
            return;
        }
        float buckets = tag.getInt("Volume") / (float) FluidTank.BUCKET_VOLUME;
        tooltip.add(Component.literal(buckets + " buckets of " + tag.getString("FluidID")));
    }

    /** Retained for callers from the 1.12 implementation. */
    public void displayInformation(ItemStack stack) {}
}
