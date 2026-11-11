package zone.moddev.mc.basepower.mixin;

import java.util.Arrays;
import com.mojang.serialization.Dynamic;
import net.minecraft.util.datafix.fixes.BlockStateData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leaves room for the saved Forge numeric IDs before vanilla flattens them. */
@Mixin(BlockStateData.class)
abstract class BlockStateDataMixin {
    @Shadow @Final @Mutable private static Dynamic<?>[] MAP;
    @Shadow @Final @Mutable private static Dynamic<?>[] BLOCK_DEFAULTS;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void basepower$expandLegacyIds(CallbackInfo callback) {
        if (MAP.length < 65536) MAP = Arrays.copyOf(MAP, 65536);
        if (BLOCK_DEFAULTS.length < 4096) BLOCK_DEFAULTS = Arrays.copyOf(BLOCK_DEFAULTS, 4096);
    }
}
