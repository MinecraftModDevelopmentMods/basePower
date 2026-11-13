package zone.moddev.mc.advantageworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.VillagerTrade;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/** Samples native loaded trades and their actual offers, never just packaged JSON. */
final class Native263MerchantProbe {
    private Native263MerchantProbe() {}

    static JsonObject verify(ServerLevel world) {
        var lookup = world.getServer().reloadableRegistries().lookup();
        var trades = lookup.lookupOrThrow(Registries.VILLAGER_TRADE);
        var ops = RegistryOps.create(JsonOps.INSTANCE, lookup);
        var merchant = new net.minecraft.world.entity.npc.villager.Villager(net.minecraft.world.entity.EntityTypes.VILLAGER, world);
        JsonArray cases = new JsonArray();
        for (String name : new String[]{"emerald_sprockets", "emerald_bioplastic"}) {
            var key = ResourceKey.create(Registries.VILLAGER_TRADE, Identifier.fromNamespaceAndPath("basepower", name));
            VillagerTrade trade = trades.getOrThrow(key).value();
            JsonObject encoded = VillagerTrade.CODEC.encodeStart(ops, trade).getOrThrow().getAsJsonObject();
            require(encoded.has("given_item_modifier") && !encoded.has("given_item_modifiers"),
                    "Loaded native trade lost its singular item modifier: " + name);
            var decoded = VillagerTrade.CODEC.parse(ops, encoded).getOrThrow();
            int min = name.equals("emerald_sprockets") ? 1 : 4;
            int max = name.equals("emerald_sprockets") ? 4 : 8;
            String item = name.equals("emerald_sprockets") ? "basepower:sprocket" : "basepower:bioplastic_ingot";
            Set<Integer> counts = offers(world, merchant, trade, item, min, max);
            require(counts.equals(offers(world, merchant, decoded, item, min, max)), "Native trade codec round trip changed offers");
            JsonObject row = new JsonObject();
            row.addProperty("trade", key.identifier().toString());
            row.addProperty("status", "PASS");
            row.addProperty("samples", 128);
            JsonArray observed = new JsonArray();
            counts.forEach(observed::add);
            row.add("counts", observed);
            cases.add(row);
            if (name.equals("emerald_bioplastic")) {
                JsonObject plural = encoded.deepCopy();
                plural.add("given_item_modifiers", plural.remove("given_item_modifier"));
                var ignored = VillagerTrade.CODEC.parse(ops, plural).getOrThrow();
                boolean rejected = false;
                try { offers(world, merchant, ignored, item, min, max); }
                catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "Native offers accepted the silently ignored historical plural modifier");
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("protocol", "NATIVE66_LOADED_MERCHANT_CODEC_OFFERS_V1");
        result.addProperty("status", "PASS");
        result.addProperty("negativeControlsRejected", 1);
        result.add("cases", cases);
        return result;
    }

    private static Set<Integer> offers(ServerLevel world, net.minecraft.world.entity.Entity merchant,
            VillagerTrade trade, String item, int min, int max) {
        var params = new LootParams.Builder(world).withParameter(LootContextParams.ORIGIN,
                new net.minecraft.world.phys.Vec3(0, 80, 0))
                .withParameter(LootContextParams.THIS_ENTITY, merchant)
                .withParameter(LootContextParams.ADDITIONAL_COST_COMPONENT_ALLOWED, net.minecraft.util.Unit.INSTANCE)
                .create(LootContextParamSets.VILLAGER_TRADE);
        Set<Integer> seen = new TreeSet<>();
        for (int seed = 1; seed <= 128; seed++) {
            var offer = trade.getOffer(new LootContext.Builder(params).withOptionalRandomSeed(seed * 0x9E3779B97F4A7C15L)
                    .create(java.util.Optional.empty()));
            require(offer != null && offer.getBaseCostA().is(Items.EMERALD) && offer.getBaseCostA().getCount() == 1
                    && offer.getCostB().isEmpty() && offer.getMaxUses() == 7 && offer.getXp() == 1
                    && offer.getPriceMultiplier() == 0.0F, "Native merchant cost/use/xp/discount changed");
            var stack = offer.getResult();
            require(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(item)
                    && stack.getCount() >= min && stack.getCount() <= max, "Native merchant modifier output changed");
            seen.add(stack.getCount());
        }
        Set<Integer> required = new TreeSet<>();
        for (int count = min; count <= max; count++) required.add(count);
        require(seen.equals(required), "Native merchant uniform modifier did not produce every retained count");
        return seen;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
