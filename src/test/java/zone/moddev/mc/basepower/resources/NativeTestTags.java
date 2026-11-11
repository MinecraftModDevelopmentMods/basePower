package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagEntry;
import net.minecraft.tags.TagFile;

import static org.junit.Assert.assertTrue;

public final class NativeTestTags {
    private NativeTestTags() {}

    public static <T> Set<T> resolve(Function<Identifier, T> element,
            Function<Identifier, Collection<T>> tag, JsonElement... files) {
        Set<T> values = new LinkedHashSet<>();
        TagEntry.Lookup<T> lookup = new TagEntry.Lookup<>() {
            @Override public T element(Identifier id, boolean required) { return element.apply(id); }
            @Override public Collection<T> tag(Identifier id) { return tag.apply(id); }
        };
        for (JsonElement json : files) {
            TagFile file = TagFile.CODEC.parse(JsonOps.INSTANCE, json)
                    .getOrThrow(IllegalArgumentException::new);
            if (file.replace()) values.clear();
            for (TagEntry entry : file.entries()) {
                assertTrue("Unresolved required tag entry: " + entry, entry.build(lookup, values::add));
            }
        }
        return values;
    }
}
