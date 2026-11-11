package zone.moddev.mc.basepower.util;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Keeps the legacy API's flat NBT fields at the native value-I/O boundary. */
public final class LegacyNbtFields {
    private static final MapCodec<CompoundTag> FIELDS = MapCodec.assumeMapUnsafe(CompoundTag.CODEC);

    private LegacyNbtFields() { }

    @SuppressWarnings("deprecation")
    public static CompoundTag read(ValueInput input) {
        return input.read(FIELDS).orElseGet(CompoundTag::new);
    }

    @SuppressWarnings("deprecation")
    public static void write(ValueOutput output, CompoundTag fields) {
        output.store(FIELDS, fields);
    }

    public static HolderLookup.Provider registries(ValueOutput output, HolderLookup.Provider fallback) {
        return output instanceof TagValueOutput tag && tag.lookup != null ? tag.lookup : fallback;
    }
}
