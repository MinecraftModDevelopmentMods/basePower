package zone.moddev.mc.advantageworks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.GameType;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

/** Synthetic detached native serializers, never evidence of an original saved player. */
final class NativeNestedIdentityProbe {
    private static final String[] ENCHANTMENTS = {"powderless", "recoil", "high_explosive", "rapid_reload"};

    static void registerCommands(FMLServerStartingEvent event) {
        if (!new File("advantage-nested-identity.allow").isFile()) return;
        event.registerServerCommand(new CommandBase() {
            @Override public String getName() { return "advnested"; }
            @Override public String getUsage(ICommandSender sender) { return "advnested run <label>"; }
            @Override public int getRequiredPermissionLevel() { return 4; }
            @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
                try {
                    if (args.length != 2 || !"run".equals(args[0]) || !args[1].matches("[A-Za-z0-9_-]+"))
                        throw new IllegalArgumentException(getUsage(sender));
                    run(server, args[1]);
                } catch (Exception error) { throw new IllegalStateException("Nested identity probe failed", error); }
            }
        });
    }

    private static ItemStack stack(String id, int count) {
        ResourceLocation name = new ResourceLocation(id);
        if (!Item.REGISTRY.containsKey(name)) throw new IllegalStateException("Missing target item " + id);
        ItemStack stack = new ItemStack(Item.REGISTRY.getObject(name), count);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("CustomName", "poweradvantage:keep-this-literal");
        tag.setByteArray("NestedBytes", new byte[]{1, -2, 3});
        tag.setIntArray("NestedInts", new int[]{3, -7, 100000});
        if (id.contains("battery")) tag.setFloat("energy", 123.5f);
        stack.setTagCompound(tag);
        return stack;
    }

    private static ItemStack musket() {
        ItemStack stack = stack("basesteam:musket", 1);
        stack.setItemDamage(5);
        for (String name : ENCHANTMENTS) {
            Enchantment enchantment = CanonicalSteamEnchantments.require("steamadvantage:" + name);
            if (enchantment == null) throw new IllegalStateException("Missing target enchantment " + name);
            stack.addEnchantment(enchantment, 1);
        }
        return stack;
    }

    private static EntityPlayerMP player(MinecraftServer server, WorldServer world) {
        EntityPlayerMP player = new EntityPlayerMP(server, world, new GameProfile(
                UUID.fromString("718399a5-0a8d-4fbb-8da8-d7c8e3063a81"), "SyntheticIdentity"), new PlayerInteractionManager(world));
        player.connection = new NetHandlerPlayServer(server, new NetworkManager(EnumPacketDirection.SERVERBOUND), player) {
            @Override public void sendPacket(Packet<?> packet) {}
        };
        player.interactionManager.setGameType(GameType.SURVIVAL);
        return player;
    }

    private static boolean item(ItemStack stack, String id) {
        return stack != null && stack.stackSize > 0 && id.equals(stack.getItem().getRegistryName().toString());
    }

    private static String legacy(String name) {
        if (name.startsWith("basepower:")) return "poweradvantage:" + name.substring("basepower:".length());
        if (name.startsWith("basesteam:")) return "steamadvantage:" + name.substring("basesteam:".length());
        if (name.startsWith("baseelectric:")) return "electricadvantage:" + name.substring("baseelectric:".length());
        return name;
    }

    private static void legacyInput(NBTBase value, JsonObject originalEnchantments) {
        if (value instanceof NBTTagList) {
            NBTTagList list = (NBTTagList)value;
            for (int i = 0; i < list.tagCount(); i++) legacyInput(list.get(i), originalEnchantments);
        } else if (value instanceof NBTTagCompound) {
            NBTTagCompound tag = (NBTTagCompound)value;
            if (tag.hasKey("Count", 1) && tag.hasKey("id", 8)) tag.setString("id", legacy(tag.getString("id")));
            for (String key : tag.getKeySet()) {
                if ("ench".equals(key) || "StoredEnchantments".equals(key)) {
                    NBTTagList enchantments = tag.getTagList(key, 10);
                    for (int i = 0; i < enchantments.tagCount(); i++) {
                        NBTTagCompound entry = enchantments.getCompoundTagAt(i);
                        Enchantment enchantment = Enchantment.getEnchantmentByID(entry.getShort("id"));
                        if (enchantment == null) throw new IllegalStateException("Unknown native enchantment ID");
                        String old = legacy(Enchantment.REGISTRY.getNameForObject(enchantment).toString());
                        if (!originalEnchantments.has(old)) throw new IllegalStateException("Missing original enchantment " + old);
                        entry.setShort("id", originalEnchantments.get(old).getAsShort());
                    }
                }
                legacyInput(tag.getTag(key), originalEnchantments);
            }
        }
    }

    private static void record(JsonArray cases, String id, NBTTagCompound input,
                               NBTTagCompound expected, NBTTagCompound actual, boolean nativeSemantics) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("inputNbtBase64", NativeNbt.encode(input));
        row.addProperty("expectedNbtBase64", NativeNbt.encode(expected));
        row.addProperty("actualNbtBase64", NativeNbt.encode(actual));
        row.addProperty("nativeSemantics", nativeSemantics);
        row.addProperty("status", expected.equals(actual) && nativeSemantics ? "PASS" : "FAIL");
        cases.add(row);
    }

    private static void run(MinecraftServer server, String label) throws Exception {
        WorldServer world = server.worldServerForDimension(0);
        if (!new File("advantage-passive-migration.allow").isFile()
                || !"AdvantageWorksMigration".equals(world.getWorldInfo().getWorldName()))
            throw new IllegalStateException("Synthetic serializer station requires a marked disposable world");
        JsonObject oldEnchantments = new JsonParser().parse(new String(Files.readAllBytes(
                new File("source-enchantments.json").toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray cases = new JsonArray();
        EntityPlayerMP original = player(server, world);
        original.inventory.setInventorySlotContents(0, stack("basepower:sprocket", 7));
        original.inventory.setInventorySlotContents(1, musket());
        original.inventory.setInventorySlotContents(36, new ItemStack(Items.DIAMOND_BOOTS));
        original.inventory.setInventorySlotContents(40, stack("baseelectric:lithium_battery", 1));
        original.getInventoryEnderChest().setInventorySlotContents(0, stack("baseelectric:lithium_battery", 1));
        original.setHealth(17f);
        original.experienceLevel = 9;
        original.experienceTotal = 127;
        original.experience = .375f;
        NBTTagCompound expectedPlayer = original.writeToNBT(new NBTTagCompound());
        // Vanilla replaces zero on read; the synthetic fixture declares a nonzero seed to preserve exactly.
        expectedPlayer.setInteger("XpSeed", 0x1234ABCD);
        NBTTagCompound inputPlayer = expectedPlayer.copy();
        legacyInput(inputPlayer, oldEnchantments);
        EntityPlayerMP restored = player(server, world);
        restored.readFromNBT(inputPlayer);
        boolean semantics = item(restored.inventory.getStackInSlot(0), "basepower:sprocket")
                && item(restored.inventory.getStackInSlot(40), "baseelectric:lithium_battery")
                && item(restored.getInventoryEnderChest().getStackInSlot(0), "baseelectric:lithium_battery");
        for (String name : ENCHANTMENTS)
            semantics &= item(restored.inventory.getStackInSlot(1), "basesteam:musket")
                    && EnchantmentHelper.getEnchantmentLevel(CanonicalSteamEnchantments.require(
                        "steamadvantage:" + name), restored.inventory.getStackInSlot(1)) == 1;
        record(cases, "synthetic/player-inventory-ender-armor-offhand", inputPlayer, expectedPlayer,
                restored.writeToNBT(new NBTTagCompound()), semantics);

        EntityItem originalDrop = new EntityItem(world, 2.5, 80, 2.5, musket());
        NBTTagCompound expectedDrop = originalDrop.writeToNBT(new NBTTagCompound());
        NBTTagCompound inputDrop = expectedDrop.copy();
        legacyInput(inputDrop, oldEnchantments);
        EntityItem restoredDrop = new EntityItem(world);
        restoredDrop.readFromNBT(inputDrop);
        record(cases, "synthetic/dropped-enchanted-item", inputDrop, expectedDrop,
                restoredDrop.writeToNBT(new NBTTagCompound()), restoredDrop.getEntityItem() != null
                        && restoredDrop.getEntityItem().getItem().getRegistryName().toString().equals("basesteam:musket"));

        NBTTagCompound expectedNested = stack("baseelectric:lithium_battery", 1).writeToNBT(new NBTTagCompound());
        NBTTagCompound inputNested = expectedNested.copy();
        legacyInput(inputNested, oldEnchantments);
        NBTTagCompound container = new NBTTagCompound();
        NBTTagList items = new NBTTagList();
        items.appendTag(inputNested);
        container.setTag("Items", items);
        NBTTagCompound outer = new NBTTagCompound();
        outer.setTag("BlockEntityTag", container);
        NBTTagCompound beforePayload = outer.copy();
        ItemStack nested = ItemStack.loadItemStackFromNBT(outer.getCompoundTag("BlockEntityTag").getTagList("Items", 10).getCompoundTagAt(0));
        record(cases, "synthetic/nested-container-stack", inputNested, expectedNested,
                nested == null ? new NBTTagCompound() : nested.writeToNBT(new NBTTagCompound()),
                nested != null && beforePayload.equals(outer) && nested.getItem().getRegistryName().toString().equals("baseelectric:lithium_battery"));

        JsonObject report = new JsonObject();
        report.addProperty("scope", "SYNTHETIC_NATIVE_SERIALIZERS_NOT_ORIGINAL_PLAYER_OR_ENTITY_PROOF");
        report.addProperty("worldInsertions", 0);
        report.addProperty("playerFileWrites", 0);
        report.add("cases", cases);
        boolean pass = true;
        for (com.google.gson.JsonElement row : cases) pass &= "PASS".equals(row.getAsJsonObject().get("status").getAsString());
        report.addProperty("status", pass ? "PASS" : "FAIL");
        NativePassiveMigrationProbe.write(new File("nested-identity-" + label + ".json"), report);
        System.out.println("ADVNESTED COMPLETE " + label);
    }
}
