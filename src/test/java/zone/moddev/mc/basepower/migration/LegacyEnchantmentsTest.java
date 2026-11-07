package zone.moddev.mc.basepower.migration;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.Test;
import static org.junit.Assert.*;

public class LegacyEnchantmentsTest {
    @Test public void resolvesOriginalMusketIdsBeforeVanillaCanAssignBindingCurse() {
        Map<Integer, String> saved = new LinkedHashMap<>();
        saved.put(10, "steamadvantage:high_explosive");
        saved.put(11, "steamadvantage:powderless");
        saved.put(12, "steamadvantage:rapid_reload");
        saved.put(13, "steamadvantage:recoil");
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Integer id : saved.keySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putShort("id", id.shortValue());
            entry.putShort("lvl", (short) 3);
            list.add(entry);
        }
        tag.put("ench", list);
        assertTrue(LegacyWorldDataHook.migrateLegacyEnchantments(tag, saved));
        assertFalse(tag.contains("ench", 9));
        for (int i = 0; i < 4; i++) {
            assertEquals(saved.get(10 + i), tag.getList("Enchantments", 10).getCompound(i).getString("id"));
            assertEquals(3, tag.getList("Enchantments", 10).getCompound(i).getShort("lvl"));
        }
        assertFalse(LegacyWorldDataHook.migrateLegacyEnchantments(tag, saved));
    }

    @Test public void honorsWorldSpecificIdsAndPreservesStoredEnchantments() {
        Map<Integer, String> saved = new LinkedHashMap<>();
        saved.put(173, "steamadvantage:rapid_reload");
        CompoundTag book = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putShort("id", (short) 173);
        entry.putShort("lvl", (short) 2);
        list.add(entry);
        book.put("StoredEnchantments", list);
        assertTrue(LegacyWorldDataHook.migrateLegacyEnchantments(book, saved));
        assertEquals("steamadvantage:rapid_reload", book.getList("StoredEnchantments", 10).getCompound(0).getString("id"));
        assertEquals(2, book.getList("StoredEnchantments", 10).getCompound(0).getShort("lvl"));
    }

    @Test(expected = IllegalStateException.class)
    public void refusesIncompleteOldListInsteadOfSilentlyDroppingAnEnchantment() {
        Map<Integer, String> saved = new LinkedHashMap<>();
        saved.put(10, "steamadvantage:high_explosive");
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putShort("id", (short) 99);
        list.add(entry);
        tag.put("ench", list);
        LegacyWorldDataHook.migrateLegacyEnchantments(tag, saved);
    }
}
