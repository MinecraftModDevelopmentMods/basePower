package zone.moddev.mc.basepower.init;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.eventbus.api.bus.BusGroup;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;

public final class ItemGroups {

	public static CreativeModeTab tab_powerAdvantage;
	private static final Set<Item> VISIBLE_ITEMS = ConcurrentHashMap.newKeySet();
	private static final DeferredRegister<CreativeModeTab> TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, PowerAdvantage.MODID);

	static {
		TABS.register("machines", () -> tab_powerAdvantage = CreativeModeTab.builder()
				.title(Component.translatable("itemGroup." + PowerAdvantage.MODID))
				.icon(() -> Items.sprocket.getDefaultInstance())
				.displayItems((parameters, output) -> VISIBLE_ITEMS.stream()
						.sorted(Comparator.comparing(ItemGroups::category, String.CASE_INSENSITIVE_ORDER)
								.thenComparing(ForgeRegistries.ITEMS::getKey))
						.forEach(output::accept))
				.build());
	}


	private ItemGroups() {}

	/** Add a registered player-facing item from Power or one of its expansions. */
	public static <T extends Item> T show(T item) {
		VISIBLE_ITEMS.add(item);
		return item;
	}

	public static void register(BusGroup bus) {
		TABS.register(bus);
	}

	private static String category(Item item) {
		StringBuilder sb = new StringBuilder();
		sb.append(ForgeRegistries.ITEMS.getKey(item).getNamespace());
		if (item instanceof BlockItem) {
			if (((BlockItem) item).getBlock().defaultBlockState().hasBlockEntity()) {
				sb.append("A");
			} else {
				sb.append("B");
			}
		} else {
			sb.append("I");
		}
		sb.append(item.getDescriptionId());
		return sb.toString();
	}
}
