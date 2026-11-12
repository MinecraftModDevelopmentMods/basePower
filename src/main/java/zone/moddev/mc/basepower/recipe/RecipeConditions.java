package zone.moddev.mc.basepower.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.MapCodec;
import java.util.Locale;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraftforge.common.crafting.conditions.ICondition;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.RecipeMode;

/** Forge recipe conditions for the existing modes and optional material tags. */
public final class RecipeConditions {
    private static final DeferredRegister<MapCodec<? extends ICondition>> SERIALIZERS =
            DeferredRegister.create(ForgeRegistries.Keys.CONDITION_SERIALIZERS, PowerAdvantage.MODID);

    static {
        SERIALIZERS.register("recipe_mode", () -> Mode.MATCH);
        SERIALIZERS.register("not_recipe_mode", () -> Mode.MISMATCH);
        SERIALIZERS.register("tag_empty", () -> Material.EMPTY);
        SERIALIZERS.register("tag_not_empty", () -> Material.NOT_EMPTY);
    }

    private RecipeConditions() {}

    public static void register(BusGroup bus) { SERIALIZERS.register(bus); }

    private record Mode(String mode, boolean match) implements ICondition {
        private static final MapCodec<Mode> MATCH = create(true);
        private static final MapCodec<Mode> MISMATCH = create(false);

        private static MapCodec<Mode> create(boolean match) {
            return Codec.STRING.fieldOf("mode").xmap(value -> new Mode(value, match), Mode::mode);
        }

        @Override public MapCodec<Mode> codec() { return match ? MATCH : MISMATCH; }
        @Override public boolean test(IContext context, DynamicOps<?> ops) {
            return (PowerAdvantage.recipeMode == RecipeMode.valueOf(mode.toUpperCase(Locale.ROOT))) == match;
        }
    }

    private record Material(Identifier tag, boolean empty) implements ICondition {
        private static final MapCodec<Material> EMPTY = create(true);
        private static final MapCodec<Material> NOT_EMPTY = create(false);

        private static MapCodec<Material> create(boolean empty) {
            return Identifier.CODEC.fieldOf("tag").xmap(id -> new Material(id, empty), Material::tag);
        }

        @Override public MapCodec<Material> codec() { return empty ? EMPTY : NOT_EMPTY; }
        @Override public boolean test(IContext context, DynamicOps<?> ops) {
            return materialTagEmpty(tag, context, ops) == empty;
        }
    }

    static boolean materialTagEmpty(Identifier tag, ICondition.IContext context, DynamicOps<?> ops) {
        // Recipes are now registry-loaded; their pending tags live on RegistryOps.
        if (context == ICondition.IContext.EMPTY && ops instanceof net.minecraft.resources.RegistryOps<?> registries) {
            return registries.getter(net.minecraft.core.registries.Registries.ITEM)
                    .flatMap(items -> items.get(ItemTags.create(tag)))
                    .map(items -> items.size() == 0).orElse(true);
        }
        return context.getTag(ItemTags.create(tag)).isEmpty();
    }
}
