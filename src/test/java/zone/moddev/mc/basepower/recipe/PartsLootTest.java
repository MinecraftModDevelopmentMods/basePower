package zone.moddev.mc.basepower.recipe;

import java.util.List;
import net.minecraft.resources.Identifier;
import org.junit.Test;
import static org.junit.Assert.*;

public class PartsLootTest {
    @Test public void addsEachLoadedModOnceAndInTheExistingOrder() {
        assertEquals(List.of(parts("basepower"), parts("basesteam"), parts("baseelectric")),
                PartsLoot.partTables(id("minecraft:chests/simple_dungeon"), "vanilla", mod -> true));
    }

    @Test public void keepsEndCityExceptionAndAbsentMods() {
        assertEquals(List.of(parts("baseelectric")), PartsLoot.partTables(
                id("minecraft:chests/end_city_treasure"), "vanilla", mod -> !mod.equals("basesteam")));
    }

    @Test public void doesNotModifyUserPacksOtherModsOrUnrelatedTables() {
        for (String source : List.of("file/chest-override", "mod:othermod", "")) {
            assertTrue(PartsLoot.partTables(id("minecraft:chests/simple_dungeon"), source, mod -> true).isEmpty());
        }
        for (String chest : List.of("minecraft:chests/desert_pyramid", "othermod:chests/simple_dungeon")) {
            assertTrue(PartsLoot.partTables(id(chest), "vanilla", mod -> true).isEmpty());
        }
    }

    private static Identifier parts(String mod) { return id(mod + ":chests/advantage_parts"); }
    private static Identifier id(String value) { return Identifier.parse(value); }
}
