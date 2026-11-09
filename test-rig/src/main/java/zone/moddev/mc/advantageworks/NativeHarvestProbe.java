package zone.moddev.mc.advantageworks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.Commands;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.GameType;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Actual survival harvesting, not loot-JSON presence or a synthetic getDrops call. */
final class NativeHarvestProbe {
    private static final File MARKER = new File("advantage-native-harvest.allow");
    private static final BlockPos POS = new BlockPos(1200, 8, 1024);

    static void register() {
        if (MARKER.isFile()) MinecraftForge.EVENT_BUS.addListener(new NativeHarvestProbe()::starting);
    }

    private void starting(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advharvest")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                    WorksController.getInstance().requireAuthorizedWorld(context.getSource().getServer());
                    try { run(context.getSource().getLevel(), StringArgumentType.getString(context, "label")); }
                    catch (Exception error) { throw new IllegalStateException("Native harvest witness failed", error); }
                    return 1;
                })));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void witness(ServerLevel world, ServerPlayer player, JsonArray rows, String id,
            String blockId, int level, Item toolItem, boolean silk, boolean allowed,
            String dropId, int minimum, int maximum, int trials) {
        witness(world, player, rows, id, blockId, level, toolItem, silk, allowed, dropId, minimum, maximum, trials, -1);
    }

    private static void witness(ServerLevel world, ServerPlayer player, JsonArray rows, String id,
            String blockId, int level, Item toolItem, boolean silk, boolean allowed,
            String dropId, int minimum, int maximum, int trials, int connectionMask) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        JsonArray samples = new JsonArray();
        JsonArray dropEvidence = new JsonArray();
        row.add("dropEvidence", dropEvidence);
        try {
            ResourceLocation key = new ResourceLocation(blockId);
            require(ForgeRegistries.BLOCKS.containsKey(key), "Missing real block: " + blockId);
            Block block = ForgeRegistries.BLOCKS.getValue(key);
            player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            for (int trial = 0; trial < trials; trial++) {
                JsonObject evidence = new JsonObject();
                evidence.addProperty("trial", trial);
                evidence.addProperty("worldTime", world.getGameTime());
                List<ItemEntity> indexed = world.getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(2));
                evidence.addProperty("removedBeforeCleanup", indexed.stream().filter(entity -> entity.isRemoved()).count());
                evidence.addProperty("liveBeforeCleanup", indexed.stream().filter(entity -> !entity.isRemoved()).count());
                dropEvidence.add(evidence);
                harvestDrops(world, new AABB(POS).inflate(2)).forEach(ItemEntity::discard);
                int remaining = harvestDrops(world, new AABB(POS).inflate(2)).size();
                evidence.addProperty("liveAfterCleanup", remaining);
                require(remaining == 0, "Harvest fixture retained a live item before placement");
                world.setBlock(POS, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                BlockState requested = block.defaultBlockState();
                if (connectionMask >= 0) {
                    int bit = 0;
                    for (Direction direction : Direction.values()) {
                        net.minecraft.world.level.block.state.properties.Property<?> property = block.getStateDefinition().getProperty(direction.getName());
                        require(property instanceof BooleanProperty, "Missing inherited conduit connection state");
                        boolean connected = (connectionMask & (1 << bit++)) != 0;
                        requested = requested.setValue((BooleanProperty) property, connected);
                        world.setBlock(POS.relative(direction), connected ? block.defaultBlockState()
                                : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
                    }
                }
                require(world.setBlock(POS, requested, 2), "Cannot place harvest witness");
                BlockState state = world.getBlockState(POS);
                require(state.equals(requested), "Native connection state changed before mining");
                boolean expectedSolid = blockId.endsWith("_ore") || blockId.equals("baseelectric:electric_conduit");
                require(state.canOcclude() == expectedSolid, "Legacy solid/non-solid property changed");
                row.addProperty("actualSolid", state.canOcclude());
                boolean fullCollision = !Shapes.joinIsNotEmpty(state.getCollisionShape(world, POS),
                        Shapes.block(), BooleanOp.NOT_SAME);
                require(fullCollision != blockId.equals("baseelectric:electric_conduit"),
                        "Legacy wire/frame/scaffold collision geometry changed");
                row.addProperty("fullCollision", fullCollision);
                if (level >= 0) {
                    require(state.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE), "Historical pickaxe tag missing");
                    require(state.is(net.minecraft.tags.BlockTags.NEEDS_STONE_TOOL) == (level == 1)
                            && !state.is(net.minecraft.tags.BlockTags.NEEDS_IRON_TOOL)
                            && !state.is(net.minecraft.tags.BlockTags.NEEDS_DIAMOND_TOOL), "Historical tool tier changed");
                    require(state.requiresCorrectToolForDrops(), "Ore no longer requires the proper tool");
                }
                ItemStack tool = toolItem == null ? ItemStack.EMPTY : new ItemStack(toolItem);
                if (silk) tool.enchant(Enchantments.SILK_TOUCH, 1);
                player.setItemInHand(InteractionHand.MAIN_HAND, tool);
                require(state.canHarvestBlock(world, POS, player) == allowed, "Tool failure/success gate changed");
                require(player.gameMode.destroyBlock(POS) && world.isEmptyBlock(POS),
                        "Native PlayerInteractionManager did not remove the block");
                List<ItemEntity> drops = harvestDrops(world, new AABB(POS).inflate(2));
                JsonArray actualDrops = new JsonArray();
                for (ItemEntity entity : drops) {
                    JsonObject actual = new JsonObject();
                    actual.addProperty("entityId", entity.getId());
                    actual.addProperty("uuid", entity.getUUID().toString());
                    actual.addProperty("item", entity.getItem().getItem().getRegistryName().toString());
                    actual.addProperty("count", entity.getItem().getCount());
                    actual.addProperty("removed", entity.isRemoved());
                    actualDrops.add(actual);
                }
                evidence.add("actualDrops", actualDrops);
                int count = 0;
                for (ItemEntity entity : drops) {
                    ItemStack stack = entity.getItem();
                    require(stack.getItem().getRegistryName().toString().equals(dropId), "Wrong actual drop: " + stack);
                    count += stack.getCount();
                }
                require(count >= minimum && count <= maximum, "Actual drop quantity changed: " + count);
                samples.add(count);
            }
            row.addProperty("status", "PASS");
        } catch (AssertionError | RuntimeException error) {
            row.addProperty("status", "FAIL");
            row.addProperty("failure", error.toString());
        }
        row.addProperty("block", blockId);
        row.addProperty("historicalHarvestLevel", level);
        row.addProperty("harvestAllowed", allowed);
        row.addProperty("silkTouch", silk);
        row.addProperty("trials", trials);
        row.addProperty("connectionMask", connectionMask);
        row.add("actualDropCounts", samples);
        rows.add(row);
    }

    private static void run(ServerLevel world, String label) throws Exception {
        require(MARKER.isFile() && label.matches("[A-Za-z0-9_-]+"), "Unmarked harvest runtime or unsafe label");
        File output = new File("advantage-works-results", "native-harvest-" + label + ".json");
        require(!output.exists(), "Refusing to overwrite harvest evidence");
        ServerPlayer player = new ServerPlayer(world.getServer(), world,
                new GameProfile(UUID.fromString("c791aefe-a372-4536-9251-27032c01f115"), "AdvWorksHarvest"));
        player.connection = new ServerGamePacketListenerImpl(world.getServer(), new Connection(PacketFlow.SERVERBOUND), player) {
            @Override public void send(Packet<?> packet) {}
        };
        player.setPos(POS.getX() + 0.5, POS.getY() + 1, POS.getZ() + 0.5);
        JsonArray rows = new JsonArray();
        String sulfur = "baseelectric:sulfur_ore", lithium = "baseelectric:li_ore";
        witness(world, player, rows, "sulfur/hand-fails", sulfur, 0, null, false, false, "", 0, 0, 1);
        witness(world, player, rows, "sulfur/axe-fails", sulfur, 0, Items.IRON_AXE, false, false, "", 0, 0, 1);
        witness(world, player, rows, "sulfur/wood", sulfur, 0, Items.WOODEN_PICKAXE, false, true, "baseelectric:sulfur_powder", 1, 4, 8);
        witness(world, player, rows, "sulfur/stone", sulfur, 0, Items.STONE_PICKAXE, false, true, "baseelectric:sulfur_powder", 1, 4, 8);
        witness(world, player, rows, "sulfur/silk", sulfur, 0, Items.WOODEN_PICKAXE, true, true, sulfur, 1, 1, 1);
        witness(world, player, rows, "lithium/hand-fails", lithium, 1, null, false, false, "", 0, 0, 1);
        witness(world, player, rows, "lithium/wood-fails", lithium, 1, Items.WOODEN_PICKAXE, false, false, "", 0, 0, 1);
        witness(world, player, rows, "lithium/gold-fails", lithium, 1, Items.GOLDEN_PICKAXE, false, false, "", 0, 0, 1);
        witness(world, player, rows, "lithium/axe-fails", lithium, 1, Items.IRON_AXE, false, false, "", 0, 0, 1);
        witness(world, player, rows, "lithium/stone", lithium, 1, Items.STONE_PICKAXE, false, true, lithium, 1, 1, 1);
        witness(world, player, rows, "lithium/diamond", lithium, 1, Items.DIAMOND_PICKAXE, false, true, lithium, 1, 1, 1);
        witness(world, player, rows, "lithium/silk", lithium, 1, Items.STONE_PICKAXE, true, true, lithium, 1, 1, 1);
        witness(world, player, rows, "frame/hand", "basepower:steel_frame", -1, null, false, true, "basepower:steel_frame", 1, 1, 1);
        witness(world, player, rows, "frame/silk", "basepower:steel_frame", -1, Items.DIAMOND_PICKAXE, true, true, "basepower:steel_frame", 1, 1, 1);
        for (int mask = 0; mask < 64; mask++) {
            for (String family : new String[] {"wire", "scaffold"}) {
                String identity = "baseelectric:" + (family.equals("wire") ? "electric_conduit" : "electric_track");
                String prefix = family + "/" + mask;
                witness(world, player, rows, prefix + "/hand", identity, -1, null, false, true, identity, 1, 1, 1, mask);
                witness(world, player, rows, prefix + "/silk", identity, -1, Items.DIAMOND_PICKAXE, true, true, identity, 1, 1, 1, mask);
            }
        }
        JsonObject solar = new JsonObject();
        solar.addProperty("id", "solar/native-solidity");
        try {
            Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("baseelectric:photovoltaic_generator"));
            require(block != null && block != net.minecraft.world.level.block.Blocks.AIR, "Missing native photovoltaic block");
            world.setBlock(POS, block.defaultBlockState(), 2);
            require(world.getBlockState(POS).canOcclude(), "Legacy photovoltaic solid=true was lost");
            solar.addProperty("actualSolid", true);
            solar.addProperty("status", "PASS");
        } catch (AssertionError | RuntimeException error) {
            solar.addProperty("status", "FAIL"); solar.addProperty("failure", error.toString());
        }
        rows.add(solar);
        world.setBlock(POS, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        for (Direction direction : Direction.values()) world.setBlock(POS.relative(direction), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2);
        harvestDrops(world, new AABB(POS).inflate(2)).forEach(ItemEntity::discard);
        JsonObject report = new JsonObject();
        report.addProperty("label", label);
        report.addProperty("minecraft", "1.18.2");
        report.addProperty("worldTime", world.getGameTime());
        report.addProperty("fixtureIsolation", "native117-live-drops-v1");
        report.addProperty("status", java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .allMatch(row -> "PASS".equals(row.getAsJsonObject().get("status").getAsString())) ? "PASS" : "FAIL");
        report.add("checks", rows);
        output.getParentFile().mkdirs();
        Files.write(output.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
        System.out.println("ADVHARVEST COMPLETE " + label);
    }

    static List<ItemEntity> harvestDrops(net.minecraft.world.level.EntityGetter world, AABB bounds) {
        // remove() only marks an entity; the chunk index can retain it until the next tick.
        return world.getEntitiesOfClass(ItemEntity.class, bounds, entity -> !entity.isRemoved());
    }
}
