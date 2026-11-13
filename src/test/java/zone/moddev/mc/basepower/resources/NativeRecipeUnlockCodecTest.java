package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import static org.junit.Assert.*;

/** Codec/reference checks only; actual unlocks and crafting run on the packaged server. */
public class NativeRecipeUnlockCodecTest {
    @BeforeClass public static void bootstrap() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerHolderSetTypes();
    }

    private static RegistryOps<com.google.gson.JsonElement> ops(boolean available) throws Exception {
        var registry = new MappedRegistry<Recipe<?>>(Registries.RECIPE, Lifecycle.stable());
        Map<ResourceKey<Recipe<?>>, Holder.Reference<Recipe<?>>> references = new LinkedHashMap<>();
        Map<TagKey<Recipe<?>>, HolderSet.Named<Recipe<?>>> tags = new LinkedHashMap<>();
        try (var files = Files.list(Path.of("src/main/resources/data/basepower/recipe"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                var key = ResourceKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("basepower",
                        file.getFileName().toString().replace(".json", "")));
                var reference = Holder.Reference.createStandAlone(registry, key);
                if (available) references.put(key, reference);
                var tag = TagKey.create(Registries.RECIPE, Identifier.fromNamespaceAndPath("basepower",
                        "discovery/" + file.getFileName().toString().replace(".json", "")));
                var named = new HolderSet.Named<Recipe<?>>(registry, tag);
                named.bind(available ? List.of(reference) : List.of());
                tags.put(tag, named);
            }
        }
        var lookup = new HolderLookup.RegistryLookup.Delegate<Recipe<?>>() {
            @Override public HolderLookup.RegistryLookup<Recipe<?>> parent() { return registry; }
            @Override public Optional<Holder.Reference<Recipe<?>>> get(ResourceKey<Recipe<?>> key) {
                return Optional.ofNullable(references.get(key));
            }
            @Override public Stream<Holder.Reference<Recipe<?>>> listElements() { return references.values().stream(); }
            @Override public Optional<HolderSet.Named<Recipe<?>>> get(TagKey<Recipe<?>> tag) {
                return Optional.ofNullable(tags.get(tag));
            }
            @Override public Stream<HolderSet.Named<Recipe<?>>> listTags() { return tags.values().stream(); }
        };
        return RegistryOps.create(JsonOps.INSTANCE, HolderLookup.Provider.create(Stream.of(lookup)));
    }

    @Test public void everyGeneratedUnlockDecodesAndNamesExactlyItsOwnRecipe() throws Exception {
        var ops = ops(true);
        int checked = 0;
        try (var files = Files.list(Path.of("src/main/resources/data/basepower/advancement/recipes"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonObject advancement = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                var conditions = advancement.getAsJsonObject("criteria").getAsJsonObject("has_recipe").get("conditions");
                var decoded = RecipeUnlockedTrigger.TriggerInstance.CODEC.parse(ops, conditions).getOrThrow();
                assertEquals(file.toString(), 1, decoded.recipes().size());
                assertEquals(Identifier.fromNamespaceAndPath("basepower", file.getFileName().toString().replace(".json", "")),
                        decoded.recipes().get(0).unwrapKey().orElseThrow().identifier());
                assertTrue(decoded.player().isEmpty());
                checked++;
            }
        }
        assertTrue("Must exercise the complete generated recipe-book set", checked > 30);
    }

    @Test public void everyInactiveUnlockDecodesWithAnEmptyTagAndNoRecipeHolder() throws Exception {
        var ops = ops(false);
        try (var files = Files.list(Path.of("src/main/resources/data/basepower/advancement/recipes"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                JsonObject advancement = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                var conditions = advancement.getAsJsonObject("criteria").getAsJsonObject("has_recipe").get("conditions");
                var decoded = RecipeUnlockedTrigger.TriggerInstance.CODEC.parse(ops, conditions).getOrThrow();
                assertEquals(file.toString(), 0, decoded.recipes().size());
                assertTrue(decoded.recipes().unwrapKey().isPresent());
            }
        }
    }

    @Test public void theOldScalarFieldUnknownRecipeAndUnknownTagCannotPassNativeDecoding() throws Exception {
        var ops = ops(true);
        assertTrue(RecipeUnlockedTrigger.TriggerInstance.CODEC.parse(ops,
                JsonParser.parseString("{\"recipe\":\"basepower:fluid_drain\"}")).isError());
        assertTrue(RecipeUnlockedTrigger.TriggerInstance.CODEC.parse(ops,
                JsonParser.parseString("{\"recipes\":[\"basepower:missing_recipe\"]}")).isError());
        assertTrue(RecipeUnlockedTrigger.TriggerInstance.CODEC.parse(ops,
                JsonParser.parseString("{\"recipes\":\"#basepower:discovery/missing_recipe\"}")).isError());
    }
}
