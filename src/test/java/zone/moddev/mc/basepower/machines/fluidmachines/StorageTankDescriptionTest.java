package zone.moddev.mc.basepower.machines.fluidmachines;

import java.io.InputStream;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.translation.LanguageMap;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual item-description generation, not just the presence of language keys. */
public class StorageTankDescriptionTest {
    @BeforeClass public static void bootstrap() throws Exception {
        Bootstrap.register();
        try (InputStream input = StorageTankDescriptionTest.class.getResourceAsStream("/assets/basepower/lang/en_US.lang")) {
            assertNotNull(input);
            LanguageMap.inject(input);
        }
    }

    @Test public void anEmptyTankItemActuallyRetainsItsTranslatedDescription() {
        ItemStack item = new ItemStack(Items.PAPER);
        new StorageTankBlock().displayInformation(item);
        assertNotNull("Generated description must be attached to the item", item.getTagCompound());
        assertEquals("Empty", item.getTagCompound().getCompoundTag("display").getTagList("Lore", 8).getStringTagAt(0));
    }

    @Test public void aFilledTankUsesTheNewTranslationWithoutChangingItsContents() {
        ItemStack item = new ItemStack(Items.PAPER);
        NBTTagCompound tag = new NBTTagCompound(); tag.setInteger("Volume", 2500); tag.setString("FluidID", "water");
        item.setTagCompound(tag);
        new StorageTankBlock().displayInformation(item);
        assertEquals("2.5 buckets of water", tag.getCompoundTag("display").getTagList("Lore", 8).getStringTagAt(0));
        assertEquals(2500, tag.getInteger("Volume"));
        assertEquals("water", tag.getString("FluidID"));
        assertEquals(1, item.stackSize);
    }
}
