package zone.moddev.mc.basepower.mixin;

import java.nio.file.Path;
import com.mojang.datafixers.DataFixer;
import com.mojang.serialization.Dynamic;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import zone.moddev.mc.basepower.migration.LegacyPlayerDataFixes;
import zone.moddev.mc.basepower.migration.LegacyWorldDataHook;
import zone.moddev.mc.basepower.migration.LegacyWorldMetadata;

/** Captures original registry identities before native level and player conversion. */
@Mixin(LevelStorageSource.class)
abstract class LevelStorageSourceMixin {
    @Inject(method = "readLevelDataTagFixed", at = @At("HEAD"))
    private static void basepower$prepareWorld(Path file, DataFixer fixer,
            CallbackInfoReturnable<Dynamic<?>> callback) {
        if (file.getFileName().toString().equals("level.dat")) {
            LegacyWorldDataHook.prepareLegacyWorld(file.toFile());
        }
    }

    @Inject(method = "readLevelDataTagRaw", at = @At("RETURN"), cancellable = true)
    private static void basepower$remapRaw(Path file, CallbackInfoReturnable<CompoundTag> callback) {
        callback.setReturnValue(LegacyWorldMetadata.remapRaw(callback.getReturnValue()));
    }

    @Inject(method = "readLightweightData", at = @At("RETURN"), cancellable = true)
    private static void basepower$remapSummary(Path file, CallbackInfoReturnable<Tag> callback) {
        callback.setReturnValue(LegacyWorldMetadata.remapLightweight(callback.getReturnValue()));
    }

    @Redirect(method = "*", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/util/datafix/DataFixTypes;updateToCurrentVersion(Lcom/mojang/datafixers/DataFixer;Lcom/mojang/serialization/Dynamic;I)Lcom/mojang/serialization/Dynamic;"))
    private static <T> Dynamic<T> basepower$preserveEmbeddedPlayer(DataFixTypes type, DataFixer fixer,
            Dynamic<T> source, int version) {
        return type == DataFixTypes.PLAYER
                ? LegacyPlayerDataFixes.updateNativeEmbeddedPlayer(type, fixer, source, version)
                : type.updateToCurrentVersion(fixer, source, version);
    }
}
