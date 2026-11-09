package zone.moddev.mc.basepower.recipe;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.entity.player.PlayerEvent;

/** Retains earned discoveries when recipe modes or material providers change. */
public final class RecipeDiscovery {
    private static final Set<String> NAMESPACES = new HashSet<>(Arrays.asList(
            "basepower", "basesteam", "baseelectric"));

    private RecipeDiscovery() {}

    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        // Disabled recipes have no reward to grant. Replay only already-earned
        // discoveries when that exact recipe becomes available on a later login.
        for (Recipe recipe : player.getServer().getRecipeManager().getRecipes()) {
            if (!NAMESPACES.contains(recipe.getId().getNamespace()) || recipe.isSpecial()
                    || player .getRecipeBook().contains(recipe)) continue;
            Advancement advancement = player.getServer().getAdvancements().getAdvancement(
                    new ResourceLocation(recipe.getId().getNamespace(), "recipes/" + recipe.getId().getPath()));
            if (advancement != null && player .getAdvancements().getOrStartProgress(advancement).isDone()) {
                player .awardRecipes(Collections.singletonList(recipe));
            }
        }
    }
}
