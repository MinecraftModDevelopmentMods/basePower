package zone.moddev.mc.basepower.compat;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import zone.moddev.mc.lib.common.recipes.CrushingRecipe;

/** Shared, reload-aware lookup for MMDLib recipes; processing remains machine-owned. */
public final class CrushingRecipes {
    private CrushingRecipes() { }

    public static ItemStack find(Level level, ItemStack stack) {
        if (level == null || level.isClientSide || stack == null || stack.isEmpty()) return null;
        SingleRecipeInput input = new SingleRecipeInput(stack.copyWithCount(1));
        return level.getRecipeManager().getRecipeFor(CrushingRecipe.TYPE.get(), input, level)
                .map(holder -> holder.value().assemble(input, level.registryAccess()))
                .filter(result -> !result.isEmpty()).map(ItemStack::copy).orElse(null);
    }

    public static ItemStack find(Level level, BlockState state) {
        return state == null ? null : find(level, new ItemStack(state.getBlock()));
    }
}
