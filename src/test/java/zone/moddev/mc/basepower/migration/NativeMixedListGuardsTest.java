package zone.moddev.mc.basepower.migration;

import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeMixedListGuardsTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        AdvantageLegacyMigrations.registerPowerAdvantage();
    }

    @Test public void mixedEntityAttributesPassengersAndRootsFailWithoutPartialMutation() {
        for (String key : List.of("Attributes", "Passengers", "Entities")) {
            CompoundTag entity = new CompoundTag();
            entity.putString("id", "foreign:vehicle");
            entity.putLong("UUIDMost", 0L);
            entity.putLong("UUIDLeast", 17L);
            ListTag mixed = new ListTag();
            mixed.add(new CompoundTag());
            mixed.add(StringTag.valueOf("foreign-witness"));
            CompoundTag level = new CompoundTag();
            ListTag entities = new ListTag();
            entities.add(entity);
            level.put("Entities", entities);
            if (key.equals("Entities")) level.put(key, mixed);
            else entity.put(key, mixed);
            CompoundTag root = new CompoundTag();
            root.putInt("DataVersion", 1343);
            root.put("Level", level);
            CompoundTag before = root.copy();
            assertThrows(key, IllegalStateException.class, () -> LegacyEntityDataFixes.prepare(root));
            assertEquals(key, before, root);
        }
    }

    @Test public void typedMachineTileGuardMarksThePassWithoutChangingAMixedNativePayload() {
        CompoundTag tile = new CompoundTag();
        tile.putString("id", "basepower:conveyor");
        ListTag tiles = new ListTag();
        tiles.add(tile);
        tiles.add(StringTag.valueOf("foreign-witness"));
        CompoundTag level = new CompoundTag();
        level.put("TileEntities", tiles);
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 512);
        root.put("Level", level);
        CompoundTag before = root.copy();
        LegacyMachineItemDataFixes.prepare(root);
        before.putBoolean("AdvantageLegacyMachineItemDataFix", true);
        assertEquals(before, root);
        LegacyMachineItemDataFixes.prepare(root);
        assertEquals("The prepared marker remains idempotent", before, root);
    }

    @Test public void typedEnchantmentGuardsReturnDetachedEmptyListsRatherThanPartialRecords() throws Exception {
        for (Class<?> owner : List.of(LegacyPlayerDataFixes.class, LegacyMachineItemDataFixes.class)) {
            var helper = owner.getDeclaredMethod("compoundList", CompoundTag.class, String.class);
            helper.setAccessible(true);
            CompoundTag tag = new CompoundTag();
            ListTag mixed = new ListTag();
            CompoundTag enchantment = new CompoundTag();
            enchantment.putString("id", "steamadvantage:steam_capacity");
            enchantment.putShort("lvl", (short) 3);
            mixed.add(enchantment);
            mixed.add(StringTag.valueOf("foreign-witness"));
            tag.put("StoredEnchantments", mixed);
            CompoundTag before = tag.copy();
            ListTag result = (ListTag) helper.invoke(null, tag, "StoredEnchantments");
            assertTrue(owner.getName(), result.isEmpty());
            result.add(new CompoundTag());
            assertEquals(owner.getName(), before, tag);
            ListTag valid = new ListTag();
            valid.add(enchantment);
            tag.put("StoredEnchantments", valid);
            assertSame("Valid typed lists retain the native mutable view", valid,
                    helper.invoke(null, tag, "StoredEnchantments"));
        }
    }

    @Test public void mixedModAndPackListsKeepEveryOriginalEntry() {
        for (String key : List.of("LoadingModList", "Enabled", "Disabled")) {
            ListTag mixed = new ListTag();
            CompoundTag mod = new CompoundTag();
            mod.putString("ModId", "poweradvantage");
            mixed.add(key.equals("LoadingModList") ? mod : StringTag.valueOf("mod:poweradvantage"));
            mixed.add(key.equals("LoadingModList") ? StringTag.valueOf("foreign-witness") : mod);
            CompoundTag root = new CompoundTag();
            if (key.equals("LoadingModList")) {
                CompoundTag forge = new CompoundTag();
                forge.put(key, mixed);
                root.put("fml", forge);
            } else {
                CompoundTag packs = new CompoundTag();
                packs.put(key, mixed);
                CompoundTag data = new CompoundTag();
                data.put("DataPacks", packs);
                root.put("Data", data);
            }
            CompoundTag before = root.copy();
            LegacyWorldMetadata.remap(root, Set.of("basepower")::contains);
            assertEquals(key, before, root);
        }
    }
}
