package zone.moddev.mc.basepower.mixin;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import zone.moddev.mc.basepower.migration.LegacyWorldDataHook;

/** Works with Base Metals' hooks without mutating pending-save cache entries. */
@Mixin(SimpleRegionStorage.class)
abstract class SimpleRegionStorageMixin {
    @Inject(method = "read", at = @At("RETURN"), cancellable = true)
    private void basepower$detachRead(ChunkPos pos,
            CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        callback.setReturnValue(LegacyWorldDataHook.copyChunkReadForMigration(callback.getReturnValue()));
    }

    @Inject(method = "upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("HEAD"))
    private void basepower$prepareChunk(CompoundTag chunk, int fallback, CompoundTag context,
            CallbackInfoReturnable<CompoundTag> callback) {
        LegacyWorldDataHook.prepareLegacyChunk(chunk);
    }

    @Inject(method = "upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/nbt/NbtUtils;getDataVersion(Lnet/minecraft/nbt/CompoundTag;I)I"))
    private void basepower$prepareInventories(CompoundTag chunk, int fallback, CompoundTag context,
            CallbackInfoReturnable<CompoundTag> callback) {
        // Both mods' HEAD hooks have run before machine/entity payloads are staged.
        LegacyWorldDataHook.prepareLegacyEntities(chunk);
    }

    @Inject(method = "upgradeChunkTag(Lnet/minecraft/nbt/CompoundTag;ILnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;",
            at = @At("RETURN"), cancellable = true)
    private void basepower$finishChunk(CompoundTag chunk, int fallback, CompoundTag context,
            CallbackInfoReturnable<CompoundTag> callback) {
        callback.setReturnValue(LegacyWorldDataHook.finalizeLegacyChunk(callback.getReturnValue()));
    }
}
