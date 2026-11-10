package zone.moddev.mc.basepower.init;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.BucketItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import zone.moddev.mc.basepower.PowerAdvantage;
import zone.moddev.mc.basepower.items.RotationTool;

/** Stable item registry for PowerAdvantage 1.13. */
@Mod.EventBusSubscriber(modid = PowerAdvantage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Items {
    public static Item starch;
    public static Item bioplastic_ingot;
    public static Item sprocket;
    public static Item rotator_tool;
    public static Item crude_oil_bucket;
    public static Item refined_oil_bucket;

    public static final Map<String, Item> allItems = new LinkedHashMap<>();
    private static boolean initialized;

    private Items() {}

    public static synchronized void init() {
        if (initialized) return;
        starch = add("starch", new Item(properties()));
        bioplastic_ingot = add("bioplastic_ingot", new Item(properties()));
        sprocket = add("sprocket", new Item(properties()));
        rotator_tool = add("rotator_tool", new RotationTool(properties().stacksTo(1)));
        Fluids.prepareOilFluids();
        // Forge 50's vanilla bucket placement still reads the eager fluid field.
        crude_oil_bucket = add("crude_oil_bucket", new BucketItem(Fluids.crude_oil,
                properties().stacksTo(1).craftRemainder(net.minecraft.world.item.Items.BUCKET)));
        refined_oil_bucket = add("refined_oil_bucket", new BucketItem(Fluids.refined_oil,
                properties().stacksTo(1).craftRemainder(net.minecraft.world.item.Items.BUCKET)));
        initialized = true;
    }

    private static Item.Properties properties() {
        return new Item.Properties();
    }

    private static Item add(String id, Item item) {
        allItems.put(id, item);
        return item;
    }

    @SubscribeEvent
    public static void register(RegisterEvent event) {
        if (!ForgeRegistries.Keys.ITEMS.equals(event.getRegistryKey())) return;
        init();
        event.register(ForgeRegistries.Keys.ITEMS, helper -> allItems.forEach((id, item) ->
                helper.register(new ResourceLocation(PowerAdvantage.MODID, id), ItemGroups.show(item))));
    }

    public static Item getItemByName(String id) { return allItems.get(id); }
    public static Set<Map.Entry<String, Item>> getAllRegisteredItems() { return allItems.entrySet(); }
}
