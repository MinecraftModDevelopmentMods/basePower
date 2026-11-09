package zone.moddev.mc.advantageworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;

import java.util.Map;

final class JsonNbtConverter {
    private JsonNbtConverter() {
    }

    static CompoundTag toCompound(JsonObject object) {
        CompoundTag result = new CompoundTag();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            result.put(entry.getKey(), toTag(entry.getValue()));
        }
        return result;
    }

    private static Tag toTag(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            throw new IllegalArgumentException("NBT values may not be null");
        }
        if (element.isJsonObject()) return toCompound(element.getAsJsonObject());
        if (element.isJsonArray()) {
            ListTag result = new ListTag();
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) result.add(toTag(child));
            return result;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return ByteTag.valueOf((byte) (primitive.getAsBoolean() ? 1 : 0));
        if (primitive.isString()) return StringTag.valueOf(primitive.getAsString());
        String number = primitive.getAsString();
        if (number.contains(".") || number.contains("e") || number.contains("E")) {
            return DoubleTag.valueOf(primitive.getAsDouble());
        }
        long value = primitive.getAsLong();
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                ? IntTag.valueOf((int) value) : LongTag.valueOf(value);
    }
}
