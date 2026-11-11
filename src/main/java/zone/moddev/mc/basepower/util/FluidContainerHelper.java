package zone.moddev.mc.basepower.util;

import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import java.util.Optional;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.wrappers.FluidBucketWrapper;

/** Uses Forge's container handling for buckets and capability-based fluid containers. */
public final class FluidContainerHelper {
    private FluidContainerHelper() {}

    public static boolean interact(Player player, InteractionHand hand,
            IAdvantageFluidHandler handler, Direction face) {
        if (player.level().isClientSide()) return false;
        ItemStack held = player.getItemInHand(hand);
        if (held.isEmpty()) return false;
        IFluidHandlerItem container = itemHandler(held.copyWithCount(1)).orElse(null);
        if (container == null) return false;
        var machine = new ForgeFluidAdapter(handler, face, () -> {
            if (handler instanceof BlockEntity tile) tile.setChanged();
        });
        // Match Forge's normal direction preference: fill the item first, then try emptying it.
        if (FluidUtil.tryFluidTransfer(container, machine, Integer.MAX_VALUE, true).isEmpty()
                && FluidUtil.tryFluidTransfer(machine, container, Integer.MAX_VALUE, true).isEmpty()) {
            return false;
        }
        if (!player.hasInfiniteMaterials()) {
            player.setItemInHand(hand, ItemUtils.createFilledResult(held, player, container.getContainer()));
        }
        return true;
    }

    public static Optional<FluidStack> getFluidContained(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        return itemHandler(stack.copyWithCount(1)).map(handler -> handler.drain(Integer.MAX_VALUE, FluidAction.SIMULATE))
                .filter(fluid -> !fluid.isEmpty());
    }

    private static Optional<IFluidHandlerItem> itemHandler(ItemStack stack) {
        // Forge 50's FluidUtil#getFluidHandler(ItemStack) is a stub. Use its standard
        // bucket adapter directly and accept explicit providers from other items.
        if (stack.getItem() instanceof BucketItem) return Optional.of(new FluidBucketWrapper(stack));
        var provider = stack.getItem().getCapabilityProvider(stack);
        return provider == null ? Optional.empty() : provider.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).resolve();
    }

    public static Fluid fluidForBucket(Item bucketItem) {
        for (Fluid fluid : net.minecraftforge.registries.ForgeRegistries.FLUIDS) {
            if (fluid.getBucket() == bucketItem && bucketItem != Items.BUCKET) return fluid;
        }
        return null;
    }

}
