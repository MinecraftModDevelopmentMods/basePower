package zone.moddev.mc.advantageworks;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.PlacementInfo;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeBookCategories;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native12111ProgressionProbeTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void nativePlacementPreservesHolesOrderAndRepeatedInputs() {
        PlacementInfo placement = PlacementInfo.createFromOptionals(List.of(
                Optional.of(Ingredient.of(Items.POTATO)), Optional.empty(),
                Optional.of(Ingredient.of(Items.CARROT)), Optional.of(Ingredient.of(Items.POTATO))));
        List<ItemStack> slots = ProgressionProbe.knownIngredients(placement,
                Set.of("minecraft:potato", "minecraft:carrot"));
        assertNotNull(slots);
        assertEquals(4, slots.size());
        assertTrue(slots.get(0).is(Items.POTATO));
        assertTrue(slots.get(1).isEmpty());
        assertTrue(slots.get(2).is(Items.CARROT));
        assertTrue(slots.get(3).is(Items.POTATO));
        assertNotSame(slots.get(0), slots.get(3));
        assertEquals(1, slots.get(0).getCount());
        assertEquals(1, slots.get(3).getCount());
    }

    @Test public void undiscoveredIngredientsCannotEnterProgression() {
        assertNull(ProgressionProbe.knownIngredients(PlacementInfo.create(Ingredient.of(Items.CARROT)),
                Set.of("minecraft:potato")));
        assertNull(ProgressionProbe.knownIngredients(PlacementInfo.create(Ingredient.of(Items.POTATO)), Set.of()));
    }

    @Test public void nativeIngredientPredicateCannotBeReplacedWithItemMembership() {
        Ingredient rejecting = new Ingredient(HolderSet.direct(Items.POTATO.builtInRegistryHolder())) {
            @Override public boolean test(ItemStack candidate) { return false; }
        };
        assertTrue(rejecting.acceptsItem(Items.POTATO.builtInRegistryHolder()));
        assertNull(ProgressionProbe.knownIngredients(PlacementInfo.create(rejecting), Set.of("minecraft:potato")));
    }

    @Test public void knownAlternativeIsTestedNatively() {
        List<ItemStack> slots = ProgressionProbe.knownIngredients(
                PlacementInfo.create(Ingredient.of(Items.POTATO, Items.CARROT)), Set.of("minecraft:carrot"));
        assertEquals(1, slots.size());
        assertTrue(slots.getFirst().is(Items.CARROT));
    }

    @Test public void impossiblePlacementDoesNotInventIngredients() {
        assertTrue(ProgressionProbe.knownIngredients(PlacementInfo.NOT_PLACEABLE, Set.of("minecraft:potato")).isEmpty());
    }

    @Test public void specialSingleInputRecipesUseMatchesDespiteHavingNoBookPlacement() {
        Recipe<SingleRecipeInput> recipe = specialRecipe();
        assertTrue(recipe.placementInfo().isImpossibleToPlace());
        List<ItemStack> inputs = ProgressionProbe.knownSingleInput(recipe, Set.of("minecraft:potato"), null);
        assertEquals(1, inputs.size());
        assertTrue(inputs.getFirst().is(Items.POTATO));
        assertEquals(1, inputs.getFirst().getCount());
        assertTrue(ProgressionProbe.knownSingleInput(recipe, Set.of("minecraft:carrot"), null).isEmpty());
        assertTrue(ProgressionProbe.knownSingleInput(recipe, Set.of(), null).isEmpty());
    }

    private static Recipe<SingleRecipeInput> specialRecipe() {
        return new Recipe<>() {
            @Override public boolean matches(SingleRecipeInput input, Level world) {
                return input.item().is(Items.POTATO) && input.item().getCount() == 1;
            }
            @Override public ItemStack assemble(SingleRecipeInput input) {
                return new ItemStack(Items.CARROT);
            }
            @Override public RecipeSerializer<? extends Recipe<SingleRecipeInput>> getSerializer() { return net.minecraft.world.item.crafting.SmeltingRecipe.SERIALIZER; }
            @Override public RecipeType<? extends Recipe<SingleRecipeInput>> getType() { return RecipeType.SMELTING; }
            @Override public boolean isSpecial() { return true; }
            @Override public boolean showNotification() { return false; }
            @Override public String group() { return ""; }
            @Override public PlacementInfo placementInfo() { return PlacementInfo.NOT_PLACEABLE; }
            @Override public RecipeBookCategory recipeBookCategory() { return RecipeBookCategories.FURNACE_FOOD; }
        };
    }
}
