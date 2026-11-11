package zone.moddev.mc.basepower.init;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativeBlockItemNamesTest {
    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        net.minecraftforge.registries.GameData.unfreezeData();
    }

    @Test public void blockItemsUseTheirExistingBlockTranslationKeys() {
        for (String name : new String[] {"fluid_drain", "fluid_storage_tank", "item_conveyor"}) {
            BlockItem item = new BlockItem(net.minecraft.world.level.block.Blocks.STONE, Blocks.itemProperties(name));
            assertEquals("block.basepower." + name, item.getDescriptionId());
        }
    }

    @Test public void ordinaryItemsKeepTheirItemTranslationKeys() {
        assertEquals("item.basepower.sprocket", new Item(new Item.Properties().setId(
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM,
                        net.minecraft.resources.Identifier.parse("basepower:sprocket")))).getDescriptionId());
    }
}
