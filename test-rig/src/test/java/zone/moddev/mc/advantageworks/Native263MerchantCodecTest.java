package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.TreeSet;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.junit.Test;

public class Native263MerchantCodecTest {
    @Test public void nativeSingularModifierActuallyChangesOffersAndOldPluralIsIgnored() throws Exception {
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerHolderSetTypes();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createReloadableLookup(
                net.minecraft.data.registries.VanillaRegistries.createWorldLookup());
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        var json = JsonParser.parseString("""
                {"wants":{"id":"minecraft:emerald","count":1},"gives":{"id":"minecraft:dirt"},
                 "max_uses":7,"xp":1,"reputation_discount":0,
                 "given_item_modifier":[{"type":"minecraft:set_count","count":{"type":"minecraft:uniform","min":4,"max":8}}]}
                """).getAsJsonObject();
        var current = VillagerTrade.CODEC.parse(ops, json).getOrThrow();
        var old = json.deepCopy();
        old.add("given_item_modifiers", old.remove("given_item_modifier"));
        var ignored = VillagerTrade.CODEC.parse(ops, old).getOrThrow();
        var world = Native117EntityContextTest.world(Level.OVERWORLD);
        var params = new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN,
                new net.minecraft.world.phys.Vec3(0, 80, 0))
                .withParameter(LootContextParams.THIS_ENTITY, new net.minecraft.world.entity.decoration.ArmorStand(world, 0, 80, 0))
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, net.minecraft.util.Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);
        var counts = new TreeSet<Integer>();
        // Invoke the actual native context constructor without starting a server.
        var constructor = LootContext.class.getDeclaredConstructor(LootParams.class,
                net.minecraft.util.RandomSource.class, net.minecraft.core.HolderGetter.Provider.class);
        constructor.setAccessible(true);
        for (int seed = 1; seed <= 128; seed++) {
            long nativeSeed = seed * 0x9E3779B97F4A7C15L;
            var offer = current.getOffer(constructor.newInstance(params, net.minecraft.util.RandomSource.create(nativeSeed), lookup));
            assertNotNull(offer);
            assertTrue(offer.getResult().is(Items.DIRT));
            assertTrue(offer.getResult().getCount() >= 4 && offer.getResult().getCount() <= 8);
            assertEquals(7, offer.getMaxUses());
            assertEquals(1, offer.getXp());
            assertEquals(0F, offer.getPriceMultiplier(), 0F);
            assertEquals(1, ignored.getOffer(constructor.newInstance(params, net.minecraft.util.RandomSource.create(nativeSeed), lookup)).getResult().getCount());
            counts.add(offer.getResult().getCount());
        }
        assertEquals(java.util.Set.of(4, 5, 6, 7, 8), counts);
    }
}
