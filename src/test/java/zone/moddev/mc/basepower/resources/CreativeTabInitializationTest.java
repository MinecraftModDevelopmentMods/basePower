package zone.moddev.mc.basepower.resources;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.CreativeModeTabEvent;
import org.junit.Test;
import zone.moddev.mc.basepower.init.ItemGroups;
import static org.junit.Assert.*;

public class CreativeTabInitializationTest {
    @Test public void itemsFromBothSidesOfRegistrationShareOneNativeTabAndAppearOnce() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        assertSame(Items.STICK, ItemGroups.show(Items.STICK));
        List<ResourceLocation> registered = new ArrayList<>();
        ItemGroups.register(new CreativeModeTabEvent.Register((setup, key, before, after) -> {
            registered.add(key);
            var builder = new CreativeModeTab.Builder(CreativeModeTab.Row.TOP, 0);
            setup.accept(builder);
            return builder.build();
        }));
        ItemGroups.show(Items.IRON_INGOT);
        ItemGroups.show(Items.STICK);
        assertEquals(List.of(new ResourceLocation("basepower:machines")), registered);
        var tab = ItemGroups.tab_powerAdvantage;
        assertEquals("itemGroup.basepower", ((TranslatableContents) tab.getDisplayName().getContents()).getKey());
        var field = CreativeModeTab.class.getDeclaredField("displayItemsGenerator");
        field.setAccessible(true);
        List<Item> entries = new ArrayList<>();
        ((CreativeModeTab.DisplayItemsGenerator) field.get(tab)).accept(null,
                (stack, visibility) -> entries.add(stack.getItem()));
        assertEquals(1, java.util.Collections.frequency(entries, Items.STICK));
        assertEquals(1, java.util.Collections.frequency(entries, Items.IRON_INGOT));
        assertFalse(entries.contains(Items.BEDROCK));
    }
}
