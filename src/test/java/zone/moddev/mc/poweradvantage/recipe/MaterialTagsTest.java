package zone.moddev.mc.poweradvantage.recipe;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.tags.Tag;
import net.minecraft.tags.TagCollection;
import net.minecraft.resources.ResourceLocation;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class MaterialTagsTest {
    @BeforeClass public static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test public void updatesNativeImmutableCollectionWithoutLosingUserEntries() {
        TagCollection<Item> tags = fixture();
        try {
            tags.getAllTags().put(new ResourceLocation("test", "must_be_immutable"), null);
            fail("Fixture must reproduce native immutable tag maps");
        } catch (UnsupportedOperationException expected) { }
        TagCollection<Item> original = tags;
        tags = MaterialTags.applyPolicy(tags, Items.APPLE, true, true);
        assertFalse(tag(original, "forge:rubber").contains(Items.APPLE));
        assertTrue(tag(tags, "forge:rubber").contains(Items.STICK));
        assertTrue(tag(tags, "forge:rubber").contains(Items.APPLE));
        assertTrue(tag(tags, "forge:ingots/rubber").contains(Items.APPLE));
        assertTrue(tag(tags, "forge:solder").contains(Items.IRON_INGOT));
        assertTrue(tag(tags, "forge:solder").contains(Items.GOLD_INGOT));
        assertTrue(tag(tags, "test:unrelated").contains(Items.REDSTONE));
        tags = MaterialTags.applyPolicy(tags, Items.APPLE, true, true);
        assertEquals(2, tag(tags, "forge:rubber").getValues().size());
        assertEquals(2, tag(tags, "forge:solder").getValues().size());
    }

    @Test public void disabledOptionsDoNotAddAliases() {
        TagCollection<Item> tags = fixture();
        assertSame(tags, MaterialTags.applyPolicy(tags, Items.APPLE, false, false));
        assertFalse(tag(tags, "forge:rubber").contains(Items.APPLE));
        assertTrue(tag(tags, "forge:solder").getValues().isEmpty());
        assertTrue(tag(tags, "test:unrelated").contains(Items.REDSTONE));
    }

    private static Tag<Item> tag(TagCollection<Item> tags, String name) { return tags.getTagOrEmpty(new ResourceLocation(name)); }

    private static TagCollection<Item> fixture() {
        Map<ResourceLocation, Tag<Item>> tags = new LinkedHashMap<>();
        tags.put(new ResourceLocation("forge:rubber"), singleton(Items.STICK));
        tags.put(new ResourceLocation("forge:ingots/lead"), singleton(Items.IRON_INGOT));
        tags.put(new ResourceLocation("forge:ingots/tin"), singleton(Items.GOLD_INGOT));
        tags.put(new ResourceLocation("test:unrelated"), singleton(Items.REDSTONE));
        return TagCollection.of(tags);
    }

    private static Tag<Item> singleton(Item item) { return Tag.fromSet(java.util.Collections.singleton(item)); }
}
