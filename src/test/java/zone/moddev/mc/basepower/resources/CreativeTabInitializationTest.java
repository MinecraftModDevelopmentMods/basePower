package zone.moddev.mc.basepower.resources;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.mojang.serialization.Lifecycle;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.RegisterEvent;
import org.junit.Test;
import zone.moddev.mc.basepower.init.ItemGroups;
import static org.junit.Assert.*;

public class CreativeTabInitializationTest {
    @Test public void itemsFromBothSidesOfRegistrationShareOneNativeTabAndAppearOnce() throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        assertSame(Items.STICK, ItemGroups.show(Items.STICK));
        var registry = new MappedRegistry<>(Registries.CREATIVE_MODE_TAB, Lifecycle.stable());
        var event = RegisterEvent.class.getDeclaredConstructor(ResourceKey.class, ForgeRegistry.class, Registry.class);
        event.setAccessible(true);
        // Plain JUnit runs without the ModLauncher event bus transformer.
        var tabs = ItemGroups.class.getDeclaredField("TABS");
        tabs.setAccessible(true);
        var dispatcher = Class.forName("net.minecraftforge.registries.DeferredRegister$EventDispatcher");
        var constructor = dispatcher.getDeclaredConstructor(DeferredRegister.class);
        constructor.setAccessible(true);
        var handleEvent = dispatcher.getDeclaredMethod("handleEvent", RegisterEvent.class);
        handleEvent.setAccessible(true);
        handleEvent.invoke(constructor.newInstance(tabs.get(null)),
                event.newInstance(Registries.CREATIVE_MODE_TAB, null, registry));
        ItemGroups.show(Items.IRON_INGOT);
        ItemGroups.show(Items.STICK);
        var id = new ResourceLocation("basepower:machines");
        assertEquals(Set.of(id), registry.keySet());
        var tab = ItemGroups.tab_powerAdvantage;
        assertSame(tab, registry.get(id));
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
