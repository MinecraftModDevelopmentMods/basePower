package zone.moddev.mc.basepower.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import zone.moddev.mc.basepower.migration.LegacyWorldDataHook;

/** Stops new decoration replacing old, already-generated workshop terrain. */
@Mixin(WorldGenRegion.class)
abstract class WorldGenRegionMixin {
    @Inject(method = "ensureCanWrite", at = @At("HEAD"), cancellable = true)
    private void basepower$protectTerrain(BlockPos pos, CallbackInfoReturnable<Boolean> callback) {
        if (LegacyWorldDataHook.shouldBlockWorldgenWrite((WorldGenRegion) (Object) this, pos)) {
            callback.setReturnValue(false);
        }
    }
}
