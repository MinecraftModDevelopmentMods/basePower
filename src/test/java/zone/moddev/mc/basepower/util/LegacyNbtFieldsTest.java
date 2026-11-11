package zone.moddev.mc.basepower.util;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.Test;

import static org.junit.Assert.*;

public class LegacyNbtFieldsTest {
    @Test
    public void preservesFlatTypedFieldsAndOpaqueNestedData() {
        CompoundTag expected = new CompoundTag();
        expected.putIntArray("Energy", new int[] {Float.floatToIntBits(12.5F)});
        expected.putByte("Facing", (byte) 4);
        CompoundTag stack = new CompoundTag();
        stack.putString("id", "other:part");
        stack.putInt("count", 17);
        CompoundTag custom = new CompoundTag();
        custom.putLong("Serial", Long.MAX_VALUE);
        stack.put("components", custom);
        ListTag items = new ListTag();
        items.add(stack);
        expected.put("Items", items);
        HolderLookup.Provider lookup = HolderLookup.Provider.create(java.util.stream.Stream.empty());
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, lookup);
        LegacyNbtFields.write(output, expected);
        CompoundTag actual = output.buildResult();
        assertEquals(expected, actual);
        assertEquals(expected, LegacyNbtFields.read(TagValueInput.create(ProblemReporter.DISCARDING, lookup, actual)));
        assertEquals(expected, actual);
        assertSame(lookup, LegacyNbtFields.registries(output, null));
    }

    @Test
    public void emptyFieldsDoNotCreateAnExtraWrapper() {
        HolderLookup.Provider lookup = HolderLookup.Provider.create(java.util.stream.Stream.empty());
        var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        LegacyNbtFields.write(output, new CompoundTag());
        assertTrue(output.buildResult().isEmpty());
        assertSame(lookup, LegacyNbtFields.registries(output, lookup));
    }
}
