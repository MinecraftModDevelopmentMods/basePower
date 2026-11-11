package zone.moddev.mc.basepower.mixin;

import com.mojang.datafixers.DataFixer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import zone.moddev.mc.basepower.migration.LegacyPlayerDataFixes;

/** Preserves modded wear and enchantments in loose player files. */
@Mixin(PlayerDataStorage.class)
abstract class PlayerDataStorageMixin {
    @Redirect(method = "*", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/util/datafix/DataFixTypes;updateToCurrentVersion(Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/nbt/CompoundTag;I)Lnet/minecraft/nbt/CompoundTag;"),
            require = 1, allow = 1)
    private CompoundTag basepower$preservePlayer(DataFixTypes type, DataFixer fixer,
            CompoundTag source, int version) {
        return LegacyPlayerDataFixes.updateNativePlayer(type, fixer, source, version);
    }
}
