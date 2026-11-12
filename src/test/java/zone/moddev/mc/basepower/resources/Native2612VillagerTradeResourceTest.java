package zone.moddev.mc.basepower.resources;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagFile;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.TradeCost;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.NumberProviders;
import net.minecraft.world.level.storage.loot.providers.number.UniformGenerator;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Native resource/field codecs only. Full Power-item trade decoding and offers need the packaged server. */
public class Native2612VillagerTradeResourceTest {
    private static final Path DATA = Path.of("src/main/resources/data");

    @BeforeClass public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        assertEquals("26.1.2", SharedConstants.getCurrentVersion().name());
    }

    @Test public void nativeTradeFieldsRetainBothHistoricalExchangeRates() throws Exception {
        verify(readTrade("emerald_sprockets"), "basepower:sprocket", 1, 4);
        verify(readTrade("emerald_bioplastic"), "basepower:bioplastic_ingot", 4, 8);
    }

    @Test public void nativeProfessionTagsAppendExactlyTheTwoRequiredTrades() throws Exception {
        verifyTag(readTag("toolsmith"), "basepower:emerald_sprockets");
        verifyTag(readTag("farmer"), "basepower:emerald_bioplastic");
    }

    @Test public void changedItemsRatesLimitsAndDiscountCannotPassTheResourceContract() throws Exception {
        JsonObject original = readTrade("emerald_sprockets");
        var wrongItem = original.deepCopy();
        wrongItem.getAsJsonObject("gives").addProperty("id", "minecraft:dirt");
        rejected(wrongItem);
        for (String field : new String[]{"max_uses", "xp", "reputation_discount"}) {
            var changed = original.deepCopy();
            changed.addProperty(field, original.get(field).getAsInt() + 1);
            rejected(changed);
        }
        var wrongCost = original.deepCopy();
        wrongCost.getAsJsonObject("wants").addProperty("count", 2);
        rejected(wrongCost);
        var wrongRange = original.deepCopy();
        wrongRange.getAsJsonArray("given_item_modifiers").get(0).getAsJsonObject()
                .getAsJsonObject("count").addProperty("max", 5);
        rejected(wrongRange);
        var additive = original.deepCopy();
        additive.getAsJsonArray("given_item_modifiers").get(0).getAsJsonObject().addProperty("add", true);
        rejected(additive);
        var unknown = original.deepCopy();
        unknown.addProperty("unreviewed_field", true);
        rejected(unknown);
    }

    @Test public void replacementAndWrongProfessionMembershipCannotPass() throws Exception {
        var replacement = readTag("toolsmith");
        replacement.addProperty("replace", true);
        assertThrows(AssertionError.class, () -> verifyTag(replacement, "basepower:emerald_sprockets"));
        assertThrows(AssertionError.class, () -> verifyTag(readTag("farmer"), "basepower:emerald_sprockets"));
    }

    @Test public void strictProviderIdentityRejectsNativeFallbackForUnknownTypes() throws Exception {
        var trade = readTrade("emerald_sprockets");
        var modifier = trade.getAsJsonArray("given_item_modifiers").get(0);
        modifier.getAsJsonObject().getAsJsonObject("count").addProperty("type", "basepower:missing_count_provider");
        // NumberProviders.CODEC explicitly falls back to an untyped UniformGenerator.
        assertTrue(LootItemFunctions.ROOT_CODEC.parse(JsonOps.INSTANCE, modifier).isSuccess());
        rejected(trade);
        modifier.getAsJsonObject().addProperty("function", "basepower:missing_item_function");
        assertTrue(LootItemFunctions.ROOT_CODEC.parse(JsonOps.INSTANCE, modifier).isError());
    }

    private static void verify(JsonObject trade, String item, int min, int max) {
        assertEquals(Set.of("wants", "gives", "max_uses", "xp", "reputation_discount", "given_item_modifiers"), trade.keySet());
        assertEquals(Set.of("id", "count"), trade.getAsJsonObject("wants").keySet());
        assertEquals(Set.of("id"), trade.getAsJsonObject("gives").keySet());
        assertEquals(item, trade.getAsJsonObject("gives").get("id").getAsString());
        var ops = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).createSerializationContext(JsonOps.INSTANCE);
        var cost = TradeCost.CODEC.parse(ops, trade.get("wants")).getOrThrow();
        assertSame(Items.EMERALD, cost.item().value());
        assertEquals(1, constant(cost.count()));
        assertEquals(7, constant(NumberProviders.CODEC.parse(ops, trade.get("max_uses")).getOrThrow()));
        assertEquals(1, constant(NumberProviders.CODEC.parse(ops, trade.get("xp")).getOrThrow()));
        assertEquals(0, constant(NumberProviders.CODEC.parse(ops, trade.get("reputation_discount")).getOrThrow()));
        var modifiers = trade.getAsJsonArray("given_item_modifiers");
        assertEquals(1, modifiers.size());
        var modifier = modifiers.get(0).getAsJsonObject();
        assertEquals(Set.of("function", "count"), modifier.keySet());
        assertEquals("minecraft:set_count", modifier.get("function").getAsString());
        var sourceCount = modifier.getAsJsonObject("count");
        assertEquals(Set.of("type", "min", "max"), sourceCount.keySet());
        assertEquals("minecraft:uniform", sourceCount.get("type").getAsString());
        var function = LootItemFunctions.ROOT_CODEC.parse(ops, modifier).getOrThrow();
        assertTrue(function instanceof SetItemCountFunction);
        var encoded = LootItemFunctions.ROOT_CODEC.encodeStart(ops, function).getOrThrow().getAsJsonObject();
        assertFalse(encoded.get("add").getAsBoolean());
        var count = NumberProviders.CODEC.parse(ops, encoded.get("count")).getOrThrow();
        assertTrue(count instanceof UniformGenerator);
        var uniform = (UniformGenerator) count;
        assertEquals(min, constant(uniform.min()));
        assertEquals(max, constant(uniform.max()));
    }

    private static int constant(net.minecraft.world.level.storage.loot.providers.number.NumberProvider value) {
        assertTrue(value instanceof ConstantValue);
        float number = ((ConstantValue) value).value();
        assertEquals((int) number, number, 0F);
        return (int) number;
    }

    private static void verifyTag(JsonObject source, String id) {
        assertEquals(Set.of("replace", "values"), source.keySet());
        TagFile decoded = TagFile.CODEC.parse(JsonOps.INSTANCE, source).getOrThrow();
        assertFalse(decoded.replace());
        assertTrue(decoded.remove().isEmpty());
        assertEquals(1, decoded.entries().size());
        var encoded = TagFile.CODEC.encodeStart(JsonOps.INSTANCE, decoded).getOrThrow().getAsJsonObject();
        assertEquals(id, encoded.getAsJsonArray("values").get(0).getAsString());
    }

    private static void rejected(JsonObject value) {
        assertThrows(AssertionError.class, () -> verify(value, "basepower:sprocket", 1, 4));
    }

    private static JsonObject readTrade(String name) throws Exception {
        return JsonParser.parseString(Files.readString(DATA.resolve("basepower/villager_trade/" + name + ".json"))).getAsJsonObject();
    }

    private static JsonObject readTag(String profession) throws Exception {
        return JsonParser.parseString(Files.readString(DATA.resolve("minecraft/tags/villager_trade/" + profession + "/level_1.json"))).getAsJsonObject();
    }
}
