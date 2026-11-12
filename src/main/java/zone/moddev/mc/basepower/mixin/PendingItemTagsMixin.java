package zone.moddev.mc.basepower.mixin;

import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.tags.TagLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import zone.moddev.mc.basepower.recipe.PendingItemTags;

/** Retains pending tag contents on the lookup passed to parallel registry loaders. */
@Mixin(TagLoader.class)
abstract class PendingItemTagsMixin {
    @Inject(method = "buildUpdatedLookups", at = @At("RETURN"), cancellable = true)
    private static void basepower$carryPendingItemTags(RegistryAccess.Frozen registries,
            List<Registry.PendingTags<?>> pending,
            CallbackInfoReturnable<List<HolderLookup.RegistryLookup<?>>> callback) {
        callback.setReturnValue(PendingItemTags.attach(callback.getReturnValue(), pending));
    }
}
