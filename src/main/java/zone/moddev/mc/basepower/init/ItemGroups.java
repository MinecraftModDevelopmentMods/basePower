package zone.moddev.mc.basepower.init;

import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.gui.FunctionalCreativeTab;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

public class ItemGroups {

	// Forge can construct the three mods on different threads.
	public static final ItemGroup tab_powerAdvantage = new FunctionalCreativeTab(
			PowerAdvantage.MODID, () -> Items.sprocket, ItemGroups::itemSort, false);

	/** Kept for the expansion jars; class initialization creates the shared tab. */
	public static void init() {
	}

	private static int itemSort(ItemStack A, ItemStack B) {
		String catA = catagorize(A);
		String catB = catagorize(B);
		return catA.compareToIgnoreCase(catB);
	}

	private static String catagorize(ItemStack i) {
		StringBuilder sb = new StringBuilder();
		Item item = i.getItem();
		sb.append(ForgeRegistries.ITEMS.getKey(item).getNamespace());
		if (item instanceof ItemBlock) {
			if (((ItemBlock) item).getBlock().hasTileEntity(((ItemBlock) item).getBlock().getDefaultState())) {
				sb.append("A");
			} else {
				sb.append("B");
			}
		} else {
			sb.append("I");
		}
		sb.append(item.getTranslationKey());
		return sb.toString();
	}
}
