package zone.moddev.mc.basepower.recipe;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.resources.Identifier;
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
        for (RecipeHolder<?> recipe : player.level().getServer().getRecipeManager().getRecipes()) {
            if (!NAMESPACES.contains(recipe.id().identifier().getNamespace()) || recipe.value().isSpecial()
                    || player .getRecipeBook().contains(recipe.id())) continue;
            AdvancementHolder advancement = player.level().getServer().getAdvancements().get(
                    Identifier.fromNamespaceAndPath(recipe.id().identifier().getNamespace(), "recipes/" + recipe.id().identifier().getPath()));
            if (advancement != null && player .getAdvancements().getOrStartProgress(advancement).isDone()) {
                player .awardRecipes(Collections.singletonList(recipe));
            }
        }
    }
}
