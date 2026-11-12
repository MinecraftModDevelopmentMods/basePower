package zone.moddev.mc.basepower.mixin;

import java.nio.file.Path;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import zone.moddev.mc.basepower.migration.LegacyWorldMetadata;

/** Applies saved registry aliases to raw level data and read-only world summaries. */
@Mixin(LevelStorageSource.class)
abstract class LevelStorageSourceMixin {
    @Inject(method = "readLevelDataTagRaw", at = @At("RETURN"), cancellable = true)
    private static void basepower$remapRaw(Path file, CallbackInfoReturnable<CompoundTag> callback) {
        callback.setReturnValue(LegacyWorldMetadata.remapRaw(callback.getReturnValue()));
    }

    @Inject(method = "readLightweightData", at = @At("RETURN"), cancellable = true)
    private static void basepower$remapSummary(Path file, CallbackInfoReturnable<Tag> callback) {
        callback.setReturnValue(LegacyWorldMetadata.remapLightweight(callback.getReturnValue()));
    }

}
