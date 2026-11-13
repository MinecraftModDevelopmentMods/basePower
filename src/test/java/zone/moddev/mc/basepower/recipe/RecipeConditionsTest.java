package zone.moddev.mc.basepower.recipe;

import com.mojang.serialization.JsonOps;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagLoader;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.crafting.conditions.ICondition;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;
import com.mojang.serialization.Lifecycle;

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

    @Test public void nativeUnboundPendingContentsAreAvailableBeforeApplyingTheReload() {
        var registry = registry();
        var key = ItemTags.create(TAG);
        var pending = registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM,
                Map.of(key, List.of(registry.getOrThrow(ResourceKey.create(Registries.ITEM, TAG))))));
        assertFalse(pending.lookup().get(key).orElseThrow().isBound());
        var lookups = PendingItemTags.attach(List.of(pending.lookup()), List.of(pending));
        var ops = RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(lookups.stream()));
        assertFalse(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, ops));
        assertTrue(RecipeConditions.materialTagEmpty(Identifier.parse("basepower:absent"), ICondition.IContext.EMPTY, ops));
        assertFalse(pending.lookup().get(key).orElseThrow().isBound());
    }

    @Test public void aNewEmptyPendingTagWinsOverThePreviousBoundContents() {
        var registry = registry(true);
        var key = ItemTags.create(TAG);
        var first = registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM,
                Map.of(key, List.of(registry.getOrThrow(ResourceKey.create(Registries.ITEM, TAG))))));
        first.apply();
        assertEquals(1, first.lookup().get(key).orElseThrow().size());
        var next = registry.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, Map.of(key, List.of())));
        var ops = RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(
                PendingItemTags.attach(List.of(next.lookup()), List.of(next)).stream()));
        assertEquals(1, next.lookup().get(key).orElseThrow().size());
        assertTrue(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, ops));
    }

    @Test public void pendingLookupsBelongToTheirOwnReloadAndDoNotChangeTheNativeDelegates() {
        var one = registry();
        var two = registry();
        var key = ItemTags.create(TAG);
        var populated = one.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM,
                Map.of(key, List.of(one.getOrThrow(ResourceKey.create(Registries.ITEM, TAG))))));
        var empty = two.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, Map.of(key, List.of())));
        var first = RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(
                PendingItemTags.attach(List.of(populated.lookup()), List.of(populated)).stream()));
        var second = RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(
                PendingItemTags.attach(List.of(empty.lookup()), List.of(empty)).stream()));
        assertFalse(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, first));
        assertTrue(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, second));
        assertFalse(RecipeConditions.materialTagEmpty(TAG, ICondition.IContext.EMPTY, first));
        List<HolderLookup.RegistryLookup<?>> unchanged = List.of(one);
        assertSame(unchanged, PendingItemTags.attach(unchanged, List.of()));
    }

    private static MappedRegistry<Item> registry() {
        return registry(false);
    }

    private static MappedRegistry<Item> registry(boolean declareTag) {
        var registry = new MappedRegistry<Item>(Registries.ITEM, Lifecycle.stable());
        registry.register(ResourceKey.create(Registries.ITEM, TAG), Items.IRON_INGOT, RegistrationInfo.BUILT_IN);
        if (declareTag) registry.createRegistrationLookup().getOrThrow(ItemTags.create(TAG));
        registry.freeze();
        return registry;
    }
}
