package zone.moddev.mc.poweradvantage.util;

import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.FluidTank;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Registry;

/** Uses Forge's container handling for buckets and capability-based fluid containers. */
public final class FluidContainerHelper {
    private FluidContainerHelper() {}

    public static boolean interact(Player player, InteractionHand hand,
            IAdvantageFluidHandler handler, Direction face) {
        if (player.level.isClientSide) return false;
        return net.minecraftforge.fluids.FluidUtil.interactWithFluidHandler(player, hand,
                new ForgeFluidAdapter(handler, face, () -> {
                    if (handler instanceof net.minecraft.world.level.block.entity.BlockEntity)
                        ((net.minecraft.world.level.block.entity.BlockEntity) handler).setChanged();
                }));
    }

    public static Fluid fluidForBucket(Item bucketItem) {
        for (Fluid fluid : net.minecraftforge.registries.ForgeRegistries.FLUIDS) {
            if (fluid.getBucket() == bucketItem && bucketItem != Items.BUCKET) return fluid;
        }
        return null;
    }

}
