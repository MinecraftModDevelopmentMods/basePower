package zone.moddev.mc.basepower.recipe;

import com.mojang.serialization.JsonOps;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class RecipeConditionsTest {
    private static final Identifier TAG = Identifier.parse("basepower:unit_pending_material");

    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void currentRegistryLoaderUsesItsPendingTagsWithoutAnOldConditionContext() {
        var named = new HolderSet.Named<Item>(BuiltInRegistries.ITEM, ItemTags.create(TAG));
        named.bind(List.of(Items.IRON_INGOT.builtInRegistryHolder()));
        var items = new HolderLookup.RegistryLookup.Delegate<Item>() {
            @Override public HolderLookup.RegistryLookup<Item> parent() { return BuiltInRegistries.ITEM; }
            @Override public Optional<HolderSet.Named<Item>> get(TagKey<Item> key) {
                return key.equals(named.key()) ? Optional.of(named) : Optional.empty();
            }
        };
        var ops = RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(Stream.of(items)));
        assertFalse(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, ops));
        named.bind(List.of());
        assertTrue(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, ops));
        assertTrue(RecipeConditions.materialTagEmpty(Identifier.parse("basepower:missing"), ICondition.IContext.EMPTY, ops));
    }

    @Test public void anExplicitConditionContextRemainsAuthoritative() {
        ICondition.IContext populated = new ICondition.IContext() {
            @Override @SuppressWarnings("unchecked") public <T> Collection<Holder<T>> getTag(TagKey<T> key) {
                return (Collection<Holder<T>>)(Collection<?>)List.of(Items.IRON_INGOT.builtInRegistryHolder());
            }
        };
        assertFalse(RecipeConditions.materialTagEmpty(TAG, populated, JsonOps.INSTANCE));
        assertTrue(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, JsonOps.INSTANCE));
        assertThrows(UnsupportedOperationException.class, () ->
                RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.TAGS_INVALID, JsonOps.INSTANCE));
    }
}
