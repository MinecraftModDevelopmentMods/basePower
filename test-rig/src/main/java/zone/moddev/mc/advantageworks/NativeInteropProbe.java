package zone.moddev.mc.advantageworks;

import net.minecraft.core.registries.BuiltInRegistries;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.modsupport.rf.TileEntityRFConverter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.Commands;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import net.neoforged.neoforge.capabilities.Capabilities;
import java.util.concurrent.atomic.AtomicInteger;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.items.IItemHandler;
import net.minecraft.core.registries.Registries;
import zone.moddev.mc.basepower.machines.conveyors.BlockConveyor;
import zone.moddev.mc.basepower.machines.conveyors.TileEntityConveyor;
import zone.moddev.mc.basepower.machines.fluidmachines.StorageTankTileEntity;
import zone.moddev.mc.basepower.util.InventoryLocks;

/** Capability and native loot witnesses, restricted to a marked disposable world. */
final class NativeInteropProbe {
    private static final File MARKER = new File("advantage-native-interop.allow");
    private static final BlockPos TANK = new BlockPos(1024, 8, 1024);
    private static final BlockPos BELT = new BlockPos(1040, 8, 1024);

    static void register() {
        if (MARKER.isFile()) NeoForge.EVENT_BUS.addListener(new NativeInteropProbe()::starting);
    }

    private void starting(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advinterop")
                .requires(source -> source.hasPermission(4))
                .then(Commands.argument("phase", StringArgumentType.word()).executes(context -> {
                    String phase = StringArgumentType.getString(context, "phase");
                    try { run(context.getSource().getLevel(), phase); }
                    catch (Throwable error) { throw new IllegalStateException("Native interop probe failed", error); }
                    System.out.println("ADVINTEROP COMPLETE " + phase);
                    return 1;
                })));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void check(JsonArray checks, String id, Runnable body) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        try { body.run(); row.addProperty("status", "PASS"); }
        catch (AssertionError | RuntimeException error) {
            row.addProperty("status", "FAIL");
            row.addProperty("failure", error.toString());
            java.io.StringWriter trace = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(trace));
            row.addProperty("traceback", trace.toString());
        }
        checks.add(row);
    }

    private static Block block(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        require(BuiltInRegistries.BLOCK.containsKey(key), "Missing registry entry: " + id);
        return zone.moddev.mc.basepower.util.RegistryValues.find(BuiltInRegistries.BLOCK, key);
    }

    private static void place(ServerLevel world, BlockPos pos, BlockState state) {
        require(pos.getX() >= 1023 && pos.getX() <= 1100 && pos.getY() == 8
                && pos.getZ() >= 1023 && pos.getZ() <= 1030, "Outside interop fixture bounds");
        world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        world.setBlock(pos, state, 3);
    }

    private static IFluidHandler fluid(BlockEntity tile) {
        require(tile != null && tile.getLevel() instanceof ServerLevel, "Fluid witness must be placed in a server world");
        return WorksCapabilities.fluid(tile, Direction.UP);
    }

    private static void energy(ServerLevel world, JsonArray checks) {
        String[] ids = {"converter_rf_steam", "converter_rf_electricity", "converter_rf_quantum", "converter_tr_electricity"};
        String[] types = {"steam", "electricity", "quantum", "electricity"};
        for (int index = 0; index < ids.length; index++) {
            final String id = ids[index];
            final ConduitType type = new ConduitType(types[index]);
            final BlockPos pos = new BlockPos(1060 + index * 8, 8, 1024);
            place(world, pos, block("basepower:" + id).defaultBlockState());
            TileEntityRFConverter tile = (TileEntityRFConverter) world.getBlockEntity(pos);
            check(checks, id + "/capability", () -> {
                IEnergyStorage storage = world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH);
                require(storage != null, "Missing native energy capability");
                require(storage == tile, "Capability does not expose the live converter");
                require(storage.receiveEnergy(-1, false) == 0 && storage.extractEnergy(-1, false) == 0,
                        "Negative transfers accepted");
                require(storage.receiveEnergy(123, true) == 123 && storage.getEnergyStored() == 0,
                        "Simulation changed converter storage");
                require(storage.receiveEnergy(123, false) == 123 && storage.getEnergyStored() == 123,
                        "Native receive lost energy");
                require(storage.extractEnergy(23, true) == 23 && storage.getEnergyStored() == 123,
                        "Extraction simulation changed storage");
                require(storage.extractEnergy(23, false) == 23 && storage.getEnergyStored() == 100,
                        "Native extraction lost energy");
                require(storage.receiveEnergy(Integer.MAX_VALUE, false) == storage.getMaxEnergyStored() - 100,
                        "Receive exceeded capacity");
                require(storage.extractEnergy(Integer.MAX_VALUE, false) == storage.getMaxEnergyStored()
                        && storage.getEnergyStored() == 0, "Extraction exceeded stored energy");
            });
            check(checks, id + "/forge-to-advantage", () -> {
                tile.setEnergy(0, type);
                tile.setOtherEnergy(100);
                double before = tile.getOtherEnergy();
                tile.powerUpdate();
                double after = tile.getOtherEnergy() + tile.convertEnergyToOther(tile.getEnergy(type), type);
                require(tile.getEnergy(type) > 0 && Math.abs(before - after) < 0.001,
                        "Conversion lost energy: before=" + before + ", after=" + after
                                + ", advantage=" + tile.getEnergy(type));
            });
            check(checks, id + "/advantage-to-forge", () -> {
                tile.setEnergy(tile.getEnergyCapacity(type) * 0.75f, type);
                tile.setOtherEnergy(0);
                double before = tile.convertEnergyToOther(tile.getEnergy(type), type);
                tile.powerUpdate();
                double after = tile.getOtherEnergy() + tile.convertEnergyToOther(tile.getEnergy(type), type);
                require(tile.getOtherEnergy() > 0 && Math.abs(before - after) < 0.001,
                        "Reverse conversion lost energy: before=" + before + ", after=" + after);
            });
            check(checks, id + "/invalidation", () -> {
                AtomicInteger invalidations = new AtomicInteger();
                var cache = BlockCapabilityCache.create(Capabilities.EnergyStorage.BLOCK, world, pos,
                        Direction.NORTH, () -> true, invalidations::incrementAndGet);
                IEnergyStorage old = cache.getCapability();
                require(old != null && old == world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH),
                        "Cache does not expose the placed converter's live capability");
                world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                require(invalidations.get() > 0, "Removing a converter did not invalidate the native cache");
                require(cache.getCapability() == null
                        && world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH) == null,
                        "Removed converter still exposes its stale capability");
                int removed = invalidations.get();
                world.setBlock(pos, block("basepower:" + id).defaultBlockState(), 3);
                require(invalidations.get() > removed, "Replacement did not invalidate the cached missing capability");
                IEnergyStorage replacement = cache.getCapability();
                require(replacement != null && replacement != old
                        && replacement == world.getCapability(Capabilities.EnergyStorage.BLOCK, pos, Direction.NORTH)
                        && replacement.getEnergyStored() == 0,
                        "Replacement did not expose a fresh, empty live converter");
            });
        }
    }

    private static void tank(ServerLevel world, JsonArray checks) {
        Block block = block("basepower:fluid_storage_tank");
        place(world, TANK, block.defaultBlockState());
        BlockEntity tile = world.getBlockEntity(TANK);
        check(checks, "fluid/simulation-conservation-and-identity", () -> {
            IFluidHandler tank = fluid(tile);
            FluidStack offered = new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1000);
            require(tank.fill(offered, IFluidHandler.FluidAction.SIMULATE) == 1000
                    && tank.getFluidInTank(0).isEmpty(), "Simulated fill changed contents");
            require(tank.fill(offered, IFluidHandler.FluidAction.EXECUTE) == 1000 && offered.getAmount() == 1000,
                    "Native fill amount or input immutability failed");
            require(tank.fill(new FluidStack(net.minecraft.world.level.material.Fluids.LAVA, 1000), IFluidHandler.FluidAction.EXECUTE) == 0,
                    "Different fluids mixed");
            require(tank.drain(500, IFluidHandler.FluidAction.SIMULATE).getAmount() == 500
                    && tank.getFluidInTank(0).getAmount() == 1000, "Simulated drain changed contents");
            FluidStack drained = tank.drain(500, IFluidHandler.FluidAction.EXECUTE);
            require(drained.getAmount() == 500 && drained.getFluid() == net.minecraft.world.level.material.Fluids.WATER
                    && tank.getFluidInTank(0).getAmount() == 500, "Drain lost fluid or identity");
        });
        check(checks, "portable-tank/native-break-drop-and-replace", () -> {
            AtomicInteger invalidations = new AtomicInteger();
            var cache = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, world, TANK,
                    Direction.UP, () -> true, invalidations::incrementAndGet);
            IFluidHandler old = cache.getCapability();
            require(old != null && old.getFluidInTank(0).getAmount() == 500, "Missing seeded tank capability");
            require(world.destroyBlock(TANK, true), "Native block destruction failed");
            require(invalidations.get() > 0 && cache.getCapability() == null
                    && world.getCapability(Capabilities.FluidHandler.BLOCK, TANK, Direction.UP) == null,
                    "Removed tank still exposes a stale native cached capability");
            List<ItemEntity> drops = world.getEntitiesOfClass(ItemEntity.class, new AABB(TANK).inflate(2));
            require(drops.size() == 1, "Expected exactly one portable tank drop; got " + drops.size());
            ItemStack item = drops.get(0).getItem().copy();
            require(item.getItem() == block.asItem() && item.getCount() == 1
                    && WorksItemData.read(item).getString("FluidID").equals("minecraft:water")
                    && WorksItemData.read(item).getInt("Volume") == 500, "Native loot lost portable fluid NBT: " + item);
            drops.get(0).discard();
            int removed = invalidations.get();
            world.setBlock(TANK, block.defaultBlockState(), 3);
            block.setPlacedBy(world, TANK, block.defaultBlockState(),
                    WorksOfflinePlayer.sentinel(world), item);
            require(invalidations.get() > removed && cache.getCapability() != old
                    && cache.getCapability() == fluid(world.getBlockEntity(TANK))
                    && cache.getCapability().getFluidInTank(0).getAmount() == 500,
                    "Replacing native drop did not restore exactly 500 mB");
        });
    }

    private static void locks(ServerLevel world, JsonArray checks) {
        place(world, BELT.west(), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        place(world, BELT, block("basepower:item_conveyor").defaultBlockState().setValue(BlockConveyor.FACING, Direction.EAST));
        place(world, BELT.east(), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        Container input = (Container) world.getBlockEntity(BELT.west());
        Container output = (Container) world.getBlockEntity(BELT.east());
        input.setItem(0, new ItemStack(net.minecraft.world.item.Items.DIRT, 16));
        TileEntityConveyor belt = (TileEntityConveyor) world.getBlockEntity(BELT);
        BlockEntity chest = world.getBlockEntity(BELT.west());
        CompoundTag state = WorksBlockData.save(chest);
        state.putString("Lock", "AdvantageInteropKey");
        chest.loadWithComponents(state, world.registryAccess());
        check(checks, "locked-chest/conveyor-refuses-extraction", () -> {
            require(InventoryLocks.isLocked(input), "Native nonempty chest lock was not detected");
            for (int i = 0; i < 160; i++) belt.tick();
            require(input.getItem(0).getCount() == 16 && output.isEmpty() && belt.isEmpty(),
                    "Conveyor bypassed locked source");
        });
        state.remove("Lock");
        chest.loadWithComponents(state, world.registryAccess());
        check(checks, "unlocked-chest/conveyor-transfers-exactly", () -> {
            require(!InventoryLocks.isLocked(input), "Cleared lock still blocks extraction");
            for (int i = 0; i < 160; i++) belt.tick();
            require(input.isEmpty() && belt.isEmpty() && output.getItem(0).getCount() == 16
                    && output.getItem(0).getItem() == net.minecraft.world.item.Items.DIRT,
                    "Unlocked native chest did not deliver exactly sixteen dirt");
        });
    }

    private static StorageTankTileEntity callbackTank(ServerLevel world, BlockPos pos) {
        require((Object) Capabilities.FluidHandler.BLOCK != Capabilities.ItemHandler.BLOCK,
                "Native fluid and item capability tokens alias each other");
        place(world, pos, zone.moddev.mc.basepower.init.Blocks.storage_tank.defaultBlockState());
        StorageTankTileEntity tile = (StorageTankTileEntity) world.getBlockEntity(pos);
        require(tile != null && tile.getLevel() == world && !tile.isRemoved(), "Callback tank is not placed in the live world");
        cyano.poweradvantage.api.fluid.FluidStack contents =
                new cyano.poweradvantage.api.fluid.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 500);
        contents.tag = new CompoundTag();
        contents.tag.putString("InteropWitness", "native1211-callback");
        tile.getTank().setFluid(contents);
        tile.setChanged();
        return tile;
    }

    private static void requireUnavailable(ServerLevel world, StorageTankTileEntity tile) {
        require(world.isLoaded(tile.getBlockPos()) && world.getBlockEntity(tile.getBlockPos()) == tile,
                "Callback witness must remain installed in a loaded world position");
        for (Direction face : Direction.values()) {
            require(world.getCapability(Capabilities.FluidHandler.BLOCK, tile.getBlockPos(), face) == null
                    && world.getCapability(Capabilities.ItemHandler.BLOCK, tile.getBlockPos(), face) == null,
                    "Unloaded callback tank recreated a sided capability: " + face);
        }
        require(world.getCapability(Capabilities.FluidHandler.BLOCK, tile.getBlockPos(), null) == null
                && world.getCapability(Capabilities.ItemHandler.BLOCK, tile.getBlockPos(), null) == null,
                "Unloaded callback tank recreated an unsided capability");
    }

    private static void capabilityCallbacks(ServerLevel world, JsonArray checks) {
        // These placed-world witnesses invoke lifecycle callbacks; they do not claim a physical chunk unload.
        check(checks, "capability/native-chunk-unloaded-callback", () -> {
            BlockPos pos = new BlockPos(1092, 8, 1024);
            StorageTankTileEntity tile = callbackTank(world, pos);
            AtomicInteger fluidInvalidations = new AtomicInteger();
            AtomicInteger itemInvalidations = new AtomicInteger();
            var fluidCache = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, world, pos,
                    Direction.UP, () -> true, fluidInvalidations::incrementAndGet);
            var itemCache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, world, pos,
                    Direction.DOWN, () -> true, itemInvalidations::incrementAndGet);
            require(fluidCache.getCapability() != null && itemCache.getCapability() != null,
                    "Placed tank lacks native fluid and item capabilities");
            CompoundTag contents = tile.getTank().writeToNBT(new CompoundTag());
            tile.onChunkUnloaded();
            require(!tile.isRemoved(), "Native unload callback unexpectedly marked the placed tank removed");
            require(fluidInvalidations.get() > 0 && itemInvalidations.get() > 0,
                    "Unload callback did not notify both native capability caches");
            require(fluidCache.getCapability() == null && itemCache.getCapability() == null,
                    "Unload callback left cached fluid or item capabilities available");
            requireUnavailable(world, tile);
            require(contents.equals(tile.getTank().writeToNBT(new CompoundTag()))
                    && tile.getContainerSize() == 0 && tile.isEmpty(), "Unload callback changed tank contents");
        });
        check(checks, "capability/native-clear-removed-and-revive", () -> {
            for (boolean clearRemoved : new boolean[] {true, false}) {
                BlockPos pos = new BlockPos(clearRemoved ? 1094 : 1096, 8, 1024);
                StorageTankTileEntity tile = callbackTank(world, pos);
                AtomicInteger fluidInvalidations = new AtomicInteger();
                AtomicInteger itemInvalidations = new AtomicInteger();
                var fluidCache = BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK, world, pos,
                        Direction.UP, () -> true, fluidInvalidations::incrementAndGet);
                var itemCache = BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK, world, pos,
                        Direction.DOWN, () -> true, itemInvalidations::incrementAndGet);
                IFluidHandler oldFluid = fluidCache.getCapability();
                IItemHandler oldItems = itemCache.getCapability();
                require(oldFluid != null && oldItems != null, "Missing original placed-world wrappers");
                FluidStack before = oldFluid.getFluidInTank(0);
                var custom = before.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
                require(before.getFluid() == net.minecraft.world.level.material.Fluids.WATER && before.getAmount() == 500
                        && custom != null && custom.copyTag().getString("InteropWitness").equals("native1211-callback")
                        && oldItems.getSlots() == 0, "Native wrappers do not expose the seeded tank contents");
                CompoundTag contents = tile.getTank().writeToNBT(new CompoundTag());
                var exposedFluid = before.save(world.registryAccess());
                tile.onChunkUnloaded();
                require(!tile.isRemoved() && fluidInvalidations.get() > 0 && itemInvalidations.get() > 0,
                        "Revival fixture was not invalidated while unremoved");
                require(fluidCache.getCapability() == null && itemCache.getCapability() == null,
                        "Invalidated tank remained available through its caches");
                requireUnavailable(world, tile);
                int fluidMissing = fluidInvalidations.get();
                int itemsMissing = itemInvalidations.get();
                if (clearRemoved) tile.clearRemoved();
                else tile.reviveCaps();
                require(fluidInvalidations.get() > fluidMissing && itemInvalidations.get() > itemsMissing,
                        "Native revival did not invalidate cached missing capabilities; clearRemoved=" + clearRemoved);
                IFluidHandler currentFluid = fluidCache.getCapability();
                IItemHandler currentItems = itemCache.getCapability();
                require(!tile.isRemoved() && currentFluid != null && currentItems != null
                        && currentFluid != oldFluid && currentItems != oldItems
                        && currentFluid == world.getCapability(Capabilities.FluidHandler.BLOCK, pos, Direction.UP)
                        && currentItems == world.getCapability(Capabilities.ItemHandler.BLOCK, pos, Direction.DOWN),
                        "Native revival did not re-query fresh live wrappers; clearRemoved=" + clearRemoved);
                require(contents.equals(tile.getTank().writeToNBT(new CompoundTag()))
                        && exposedFluid.equals(currentFluid.getFluidInTank(0).save(world.registryAccess()))
                        && currentItems.getSlots() == 0 && tile.getContainerSize() == 0 && tile.isEmpty(),
                        "Native revival changed fluid identity, amount, components or empty item contents");
            }
        });
    }

    private static void run(ServerLevel world, String phase) throws Exception {
        require(MARKER.isFile() && world.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration"), "Unmarked world");
        require(phase.equals("exercise") || phase.equals("restored"), "Unknown interop phase");
        JsonArray checks = new JsonArray();
        if (phase.equals("exercise")) {
            energy(world, checks);
            tank(world, checks);
            locks(world, checks);
            capabilityCallbacks(world, checks);
        } else {
            check(checks, "portable-tank/full-process-restart", () -> {
                FluidStack stack = fluid(world.getBlockEntity(TANK)).getFluidInTank(0);
                require(stack.getFluid() == net.minecraft.world.level.material.Fluids.WATER && stack.getAmount() == 500,
                        "Restart lost portable tank contents");
            });
            check(checks, "conveyor/full-process-restart", () -> {
                Container output = (Container) world.getBlockEntity(BELT.east());
                require(output.getItem(0).getItem() == net.minecraft.world.item.Items.DIRT
                        && output.getItem(0).getCount() == 16, "Restart changed exact conveyor output");
            });
        }
        JsonObject report = new JsonObject();
        report.addProperty("phase", phase);
        report.addProperty("worldTime", world.getGameTime());
        report.addProperty("status", java.util.stream.StreamSupport.stream(checks.spliterator(), false)
                .allMatch(row -> row.getAsJsonObject().get("status").getAsString().equals("PASS")) ? "PASS" : "FAIL");
        report.add("checks", checks);
        if (net.neoforged.fml.ModList.get().isLoaded("basemetals")) {
            JsonArray shared = new JsonArray();
            check(shared, "basemetals/native-power-crushing", () -> sharedCrushing(world));
            report.add("sharedChecks", shared);
            if (java.util.stream.StreamSupport.stream(shared.spliterator(), false)
                    .anyMatch(row -> !row.getAsJsonObject().get("status").getAsString().equals("PASS")))
                report.addProperty("status", "FAIL");
        }
        File destination = new File("advantage-works-results", "native-interop-" + phase + ".json");
        require(!destination.exists(), "Refusing to overwrite interop evidence");
        destination.getParentFile().mkdirs();
        Files.write(destination.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static void sharedCrushing(ServerLevel world) {
        ResourceLocation recipeId = ResourceLocation.fromNamespaceAndPath("basepower", "crushing_potato_starch");
        var holder = world.getRecipeManager().byKey(recipeId).orElseThrow(
                () -> new AssertionError("Missing Power recipe under shared MMDLib crushing: " + recipeId));
        var recipe = (net.minecraft.world.item.crafting.Recipe<net.minecraft.world.item.crafting.SingleRecipeInput>) holder.value();
        var type = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        require(ResourceLocation.fromNamespaceAndPath("mmdlib", "crushing").equals(type), "Wrong loaded crushing type: " + type);
        for (var potato : List.of(net.minecraft.world.item.Items.POTATO, net.minecraft.world.item.Items.POISONOUS_POTATO)) {
            var input = new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(potato, 3));
            require(recipe.matches(input, world), "Shared crusher rejected " + potato);
            ItemStack result = recipe.assemble(input, world.registryAccess());
            require(result.getCount() == 1 && ResourceLocation.fromNamespaceAndPath("basepower", "starch").equals(
                    BuiltInRegistries.ITEM.getKey(result.getItem())), "Incorrect shared crushing result: " + result);
            require(input.getItem(0).getCount() == 3, "Assembling a recipe preview mutated its input");
        }
        require(!recipe.matches(new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(net.minecraft.world.item.Items.DIRT)), world),
                "Shared crushing accepted unrelated input");
    }
}
