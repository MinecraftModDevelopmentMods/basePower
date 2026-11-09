package zone.moddev.mc.basepower.init;

import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.BlockItem;
import net.minecraftforge.event.CreativeModeTabEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;

@Mod.EventBusSubscriber(modid = PowerAdvantage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ItemGroups {

	public static CreativeModeTab tab_powerAdvantage;
	private static final Set<Item> VISIBLE_ITEMS = ConcurrentHashMap.newKeySet();

	private ItemGroups() {}

	/** Add a registered player-facing item from Power or one of its expansions. */
	public static <T extends Item> T show(T item) {
		VISIBLE_ITEMS.add(item);
		return item;
	}

	@SubscribeEvent
	public static void register(CreativeModeTabEvent.Register event) {
		tab_powerAdvantage = event.registerCreativeModeTab(new ResourceLocation(PowerAdvantage.MODID, "machines"),
				builder -> builder.title(Component.translatable("itemGroup." + PowerAdvantage.MODID))
						.icon(() -> Items.sprocket.getDefaultInstance())
						.displayItems((parameters, output) -> VISIBLE_ITEMS.stream()
								.sorted(Comparator.comparing(ItemGroups::category, String.CASE_INSENSITIVE_ORDER)
										.thenComparing(ForgeRegistries.ITEMS::getKey))
								.forEach(output::accept)));
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
