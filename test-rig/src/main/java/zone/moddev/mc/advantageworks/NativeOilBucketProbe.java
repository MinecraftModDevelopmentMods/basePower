package zone.moddev.mc.advantageworks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.registries.ForgeRegistries;

/** Real survival interactions on live fluid blocks and native Power tanks. */
final class NativeOilBucketProbe {
    private static final Path MARKER = Path.of("advantage-native-oil-bucket.allow");
    private static final Path RECEIPT = Path.of("advantage-works-results", "native-oil-buckets.json");
    private static final InteractionHand HAND = InteractionHand.MAIN_HAND;

    private NativeOilBucketProbe() {}

    private static final class BucketActor extends WorksOfflinePlayer {
        private BlockPos menuPosition;
        private int menuRequests;

        BucketActor(ServerLevel world) {
            super(world, new GameProfile(UUID.fromString("e95991d9-1ed7-4a81-91f6-0f42e21667a0"), "OilBucketProbe"));
        }

        @Override public void openMenu(MenuProvider provider, BlockPos pos) {
            // The offline actor records the real block's menu fallback at the packet boundary.
            menuPosition = pos.immutable();
            menuRequests++;
        }
    }

    static void register() {
        if (Files.isRegularFile(MARKER)) MinecraftForge.EVENT_BUS.addListener(NativeOilBucketProbe::commands);
    }

    private static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advoilbuckets").requires(source -> source.hasPermission(4))
                .executes(context -> {
                    try {
                        run(context.getSource().getLevel());
                    } catch (Exception error) {
                        throw new IllegalStateException("Native oil bucket probe failed", error);
                    }
                    System.out.println("ADVOILBUCKETS COMPLETE");
                    return 1;
                }));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void check(JsonArray results, String id, Consumer<JsonObject> action) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        try {
            action.accept(row);
            row.addProperty("status", "PASS");
        } catch (AssertionError | RuntimeException error) {
            row.addProperty("status", "FAIL");
            row.addProperty("failure", error.toString());
            var trace = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            row.addProperty("traceback", trace.toString());
        }
        results.add(row);
    }

    private static void hand(WorksOfflinePlayer player, Item item) {
        player.setItemInHand(HAND, new ItemStack(item));
    }

    private static void expectHand(WorksOfflinePlayer player, Item item) {
        ItemStack stack = player.getItemInHand(HAND);
        require(stack.is(item) && stack.getCount() == 1, "Wrong held item or count: " + stack);
        int inventoryItems = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            inventoryItems += player.getInventory().getItem(slot).getCount();
        }
        require(inventoryItems == 1, "Interaction duplicated or lost inventory: " + inventoryItems);
    }

    private static void worldRoundTrip(ServerLevel world, WorksOfflinePlayer player, BlockPos basin,
                                       Fluid oil, Item filled, JsonObject row) {
        world.setBlockAndUpdate(basin.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(basin, Blocks.AIR.defaultBlockState());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            world.setBlockAndUpdate(basin.relative(direction), Blocks.STONE.defaultBlockState());
        }
        player.moveTo(basin.getX() + 0.5, basin.getY() + 1.5, basin.getZ() + 0.5, 0, 90);
        hand(player, filled);
        var placed = player.gameMode.useItem(player, world, player.getItemInHand(HAND), HAND);
        require(placed.consumesAction(), "Filled bucket world use returned " + placed);
        var source = world.getFluidState(basin);
        require(source.isSource() && source.getType() == oil, "Bucket did not place the exact oil source: " + source);
        expectHand(player, Items.BUCKET);
        row.addProperty("placedSource", ForgeRegistries.FLUIDS.getKey(source.getType()).toString());
        row.addProperty("placementBucket", "minecraft:bucket");
        var pickedUp = player.gameMode.useItem(player, world, player.getItemInHand(HAND), HAND);
        require(pickedUp.consumesAction(), "Empty bucket world use returned " + pickedUp);
        require(world.getFluidState(basin).isEmpty() && world.getBlockState(basin).isAir(), "Oil source remains after pickup");
        expectHand(player, filled);
        row.addProperty("pickupBucket", ForgeRegistries.ITEMS.getKey(filled).toString());
    }

    private static IFluidHandler machine(ServerLevel world, BlockPos pos, Fluid oil, int amount) {
        world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        var block = ForgeRegistries.BLOCKS.getValue(ResourceLocation.fromNamespaceAndPath("basepower", "fluid_storage_tank"));
        require(block != null, "Storage tank block unavailable");
        world.setBlockAndUpdate(pos, block.defaultBlockState());
        var tile = world.getBlockEntity(pos);
        require(tile != null, "Storage tank tile unavailable");
        var tank = tile.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP)
                .orElseThrow(() -> new AssertionError("Storage tank capability unavailable"));
        require(tank.getTankCapacity(0) == 4000, "Storage tank capacity changed");
        if (amount > 0) require(tank.fill(new FluidStack(oil, amount), IFluidHandler.FluidAction.EXECUTE) == amount,
                "Failed to seed exact tank quantity");
        expectFluid(tank, oil, amount);
        return tank;
    }

    private static void expectFluid(IFluidHandler tank, Fluid oil, int amount) {
        var state = tank.getFluidInTank(0);
        require(state.getAmount() == amount && (amount == 0 ? state.isEmpty() : state.getFluid() == oil),
                "Wrong fluid identity or amount: " + state + ", expected " + amount + " mB");
    }

    private static boolean clickTank(ServerLevel world, WorksOfflinePlayer player, BlockPos pos) {
        player.moveTo(pos.getX() + 0.5, pos.getY() + 1.5, pos.getZ() + 1.5, 180, 35);
        var hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0, 0.5, 0), Direction.UP, pos, false);
        return player.gameMode.useItemOn(player, world, player.getItemInHand(HAND), HAND, hit).consumesAction();
    }

    private static void machineRoundTrip(ServerLevel world, BucketActor player, BlockPos pos,
                                         Fluid oil, Item filled, JsonObject row) {
        IFluidHandler tank = machine(world, pos, oil, 0);
        hand(player, filled);
        int beforeMenus = player.menuRequests;
        require(clickTank(world, player, pos), "Tank did not consume filled bucket interaction");
        expectFluid(tank, oil, 1000);
        expectHand(player, Items.BUCKET);
        row.addProperty("depositedMb", tank.getFluidInTank(0).getAmount());
        require(clickTank(world, player, pos), "Tank did not consume empty bucket interaction");
        expectFluid(tank, oil, 0);
        expectHand(player, filled);
        require(player.menuRequests == beforeMenus, "Successful bucket transfer opened a fallback menu");
        row.addProperty("returnedMb", 1000 - tank.getFluidInTank(0).getAmount());
    }

    private static void noLoss(ServerLevel world, BucketActor player, BlockPos pos, Fluid oil,
                               Item held, int amount, JsonObject row) {
        IFluidHandler tank = machine(world, pos, oil, amount);
        hand(player, held);
        int beforeMenus = player.menuRequests;
        require(clickTank(world, player, pos) && player.menuRequests == beforeMenus + 1
                && pos.equals(player.menuPosition), "Blocked transfer did not reach the original tank menu");
        expectFluid(tank, oil, amount);
        expectHand(player, held);
        require(world.getFluidState(pos.above()).isEmpty(), "Blocked transfer spilled into the world");
        row.addProperty("beforeMb", amount);
        row.addProperty("afterMb", tank.getFluidInTank(0).getAmount());
        row.addProperty("heldBucket", ForgeRegistries.ITEMS.getKey(held).toString());
        row.addProperty("menuFallbackRequests", player.menuRequests - beforeMenus);
    }

    private static void run(ServerLevel world) throws Exception {
        require(Files.isRegularFile(MARKER) && !Files.exists(RECEIPT)
                && world.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration"), "Unmarked or reused world");
        var player = new BucketActor(world);
        require(player.gameMode.getGameModeForPlayer() == GameType.SURVIVAL && !player.hasInfiniteMaterials()
                && !world.players().contains(player), "Expected a non-joined survival actor");
        JsonArray checks = new JsonArray();
        for (int index = 0; index < 2; index++) {
            String name = index == 0 ? "crude_oil" : "refined_oil";
            var oil = ForgeRegistries.FLUIDS.getValue(ResourceLocation.fromNamespaceAndPath("basepower", name));
            var filled = ForgeRegistries.ITEMS.getValue(ResourceLocation.fromNamespaceAndPath("basepower", name + "_bucket"));
            require(oil != null && filled instanceof net.minecraft.world.item.BucketItem, "Oil source or bucket unavailable");
            int x = 1072 + 16 * index;
            BlockPos basin = new BlockPos(x, 8, 1025), tank = new BlockPos(x, 8, 1029);
            require(world.isLoaded(basin) && world.isLoaded(tank), "Bucket witness chunks not ready");
            check(checks, name + "/world-roundtrip", row -> worldRoundTrip(world, player, basin, oil, filled, row));
            check(checks, name + "/machine-roundtrip", row -> machineRoundTrip(world, player, tank, oil, filled, row));
            check(checks, name + "/machine-below-bucket", row -> noLoss(world, player, tank, oil, Items.BUCKET, 375, row));
            check(checks, name + "/machine-insufficient-room", row -> noLoss(world, player, tank, oil, filled, 3500, row));
            check(checks, name + "/machine-full", row -> noLoss(world, player, tank, oil, filled, 4000, row));
        }
        JsonObject receipt = new JsonObject();
        receipt.addProperty("worldTime", world.getGameTime());
        receipt.addProperty("gameMode", "survival");
        receipt.add("checks", checks);
        Files.createDirectories(RECEIPT.getParent());
        Files.writeString(RECEIPT, new GsonBuilder().setPrettyPrinting().create().toJson(receipt),
                java.nio.file.StandardOpenOption.CREATE_NEW);
    }
}
