package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.advancements.triggers.RecipeUnlockedTrigger;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.Recipe;
import org.junit.BeforeClass;
import org.junit.Test;

public class Native263RecipeTriggerTest {
    private static final String ID = "basepower:future_mode";
    private static final String TAG = "basepower:discovery/future_mode";

    @BeforeClass public static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerHolderSetTypes();
    }

    private static RegistryOps<com.google.gson.JsonElement> ops(List<String> members) {
        var registry = new MappedRegistry<Recipe<?>>(Registries.RECIPE, Lifecycle.stable());
        var tag = TagKey.create(Registries.RECIPE, Identifier.parse(TAG));
        var named = new HolderSet.Named<Recipe<?>>(registry, tag);
        List<Holder<Recipe<?>>> references = members.stream().map(id -> (Holder<Recipe<?>>)
                Holder.Reference.createStandAlone(registry, ResourceKey.create(Registries.RECIPE, Identifier.parse(id)))).toList();
        named.bind(references);
        var lookup = new HolderLookup.RegistryLookup.Delegate<Recipe<?>>() {
            @Override public HolderLookup.RegistryLookup<Recipe<?>> parent() { return registry; }
            @Override public Optional<Holder.Reference<Recipe<?>>> get(ResourceKey<Recipe<?>> key) { return Optional.empty(); }
            @Override public Stream<Holder.Reference<Recipe<?>>> listElements() { return Stream.empty(); }
            @Override public Optional<HolderSet.Named<Recipe<?>>> get(TagKey<Recipe<?>> key) {
                return key.equals(tag) ? Optional.of(named) : Optional.empty();
            }
            @Override public Stream<HolderSet.Named<Recipe<?>>> listTags() { return Stream.of(named); }
        };
        return RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(Stream.of(lookup)));
    }

    private static RecipeUnlockedTrigger.TriggerInstance decode(List<String> members) {
        return RecipeUnlockedTrigger.TriggerInstance.CODEC.parse(ops(members),
                JsonParser.parseString("{\"recipes\":\"#" + TAG + "\"}")).getOrThrow();
    }

    @Test public void activeNativeTagContainsExactlyTheExpectedRecipeKey() {
        var trigger = decode(List.of(ID));
        Native263RecipeConditions.requireTriggerTag(ID, TAG, true, trigger);
        assertEquals(1, trigger.recipes().size());
    }

    @Test public void inactiveNativeTagDecodesWithoutAnyRecipeHolder() {
        var trigger = decode(List.of());
        Native263RecipeConditions.requireTriggerTag(ID, TAG, false, trigger);
        assertEquals(0, trigger.recipes().size());
        assertTrue(trigger.recipes().unwrapKey().isPresent());
    }

    @Test public void emptyActiveAndNonemptyInactiveAndWrongOrExtraMembersFail() {
        assertThrows(IllegalStateException.class, () -> Native263RecipeConditions.requireTriggerTag(ID, TAG, true, decode(List.of())));
        assertThrows(IllegalStateException.class, () -> Native263RecipeConditions.requireTriggerTag(ID, TAG, false, decode(List.of(ID))));
        for (List<String> members : List.of(List.of("basepower:wrong"), List.of(ID, "basepower:extra"), List.of(ID, ID)))
            assertThrows(IllegalStateException.class, () -> Native263RecipeConditions.requireTriggerTag(ID, TAG, true, decode(members)));
    }

    @Test public void aDifferentNamedTagCannotStandInForTheDiscoveryTag() {
        assertThrows(IllegalStateException.class, () -> Native263RecipeConditions.requireTriggerTag(ID,
                "basepower:discovery/wrong", true, decode(List.of(ID))));
    }

    @Test public void nativeCodecRejectsUnknownTagAndHardRecipeReferenceAndOldScalarField() {
        for (String json : List.of("{\"recipes\":\"#basepower:discovery/unknown\"}",
                "{\"recipes\":[\"basepower:unknown\"]}", "{\"recipe\":\"basepower:future_mode\"}"))
            assertTrue(RecipeUnlockedTrigger.TriggerInstance.CODEC.parse(ops(List.of()), JsonParser.parseString(json)).isError());
    }
}
