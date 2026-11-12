package zone.moddev.mc.basepower.mixin;

import java.nio.file.Path;
import com.mojang.serialization.Dynamic;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import zone.moddev.mc.basepower.migration.LegacyWorldDataHook;

/** Captures the locked, selected world before native file relocation and data fixing. */
@Mixin(LevelStorageSource.LevelStorageAccess.class)
abstract class SelectedWorldStorageMixin {
    @Shadow public abstract Path getLevelPath(LevelResource resource);

    @Inject(method = "getUnfixedDataTag", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/storage/LevelStorageSource;readLevelDataTagRaw(Ljava/nio/file/Path;)Lnet/minecraft/nbt/CompoundTag;"))
    private void basepower$prepareWorld(boolean useFallback, CallbackInfoReturnable<Dynamic<?>> callback) {
        // The native method has checked its session lock. Never mutate previews or fallback files.
        if (!useFallback) {
            LegacyWorldDataHook.prepareLegacyWorld(getLevelPath(LevelResource.LEVEL_DATA_FILE).toFile());
        }
    }
}
