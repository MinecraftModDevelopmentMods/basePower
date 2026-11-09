package zone.moddev.mc.basepower.init;

import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraftforge.event.village.VillagerTradesEvent;

/** The old farmer and toolsmith careers now have their own vanilla professions. */
public final class Villages {
    private Villages() {}

    public static void onTrades(VillagerTradesEvent event) {
        if (event.getType() == VillagerProfession.TOOLSMITH) {
            event.getTrades().get(1).add((villager, random) -> new MerchantOffer(
                    new ItemStack(net.minecraft.world.item.Items.EMERALD),
                    new ItemStack(Items.sprocket, 1 + random.nextInt(4)), 7, 1, 0.0F));
        } else if (event.getType() == VillagerProfession.FARMER) {
            event.getTrades().get(1).add((villager, random) -> new MerchantOffer(
                    new ItemStack(net.minecraft.world.item.Items.EMERALD),
                    new ItemStack(Items.bioplastic_ingot, 4 + random.nextInt(5)), 7, 1, 0.0F));
        }
    }
}
