package zone.moddev.mc.advantageworks;

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
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
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
        if (MARKER.isFile()) RegisterCommandsEvent.BUS.addListener(new NativeInteropProbe()::starting);
    }

    private void starting(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advinterop")
                .requires(source -> source.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_OWNER))
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
        Identifier key = Identifier.parse(id);
        require(ForgeRegistries.BLOCKS.containsKey(key), "Missing registry entry: " + id);
        return ForgeRegistries.BLOCKS.getValue(key);
    }

    private static void place(ServerLevel world, BlockPos pos, BlockState state) {
        require(pos.getX() >= 1023 && pos.getX() <= 1100 && pos.getY() == 8
                && pos.getZ() >= 1023 && pos.getZ() <= 1030, "Outside interop fixture bounds");
        world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        world.setBlock(pos, state, 3);
    }

    private static IFluidHandler fluid(BlockEntity tile) {
        return tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, Direction.UP)
                .orElseThrow(() -> new AssertionError("Missing native fluid capability"));
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
                IEnergyStorage storage = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, Direction.NORTH)
                        .orElseThrow(() -> new AssertionError("Missing Forge Energy capability"));
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
                LazyOptional<IEnergyStorage> old = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, Direction.NORTH);
                require(old.isPresent(), "Missing original capability");
                world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
                require(!old.isPresent(), "Removed converter still exposes its stale capability");
                world.setBlock(pos, block("basepower:" + id).defaultBlockState(), 3);
                LazyOptional<IEnergyStorage> replacement = world.getBlockEntity(pos).getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY, Direction.NORTH);
                require(replacement.isPresent() && replacement != old, "Replacement did not expose a fresh capability");
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
            LazyOptional<IFluidHandler> old = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, Direction.UP);
            require(world.destroyBlock(TANK, true), "Native block destruction failed");
            require(!old.isPresent(), "Removed tank still exposes a stale capability");
            List<ItemEntity> drops = world.getEntitiesOfClass(ItemEntity.class, new AABB(TANK).inflate(2));
            require(drops.size() == 1, "Expected exactly one portable tank drop; got " + drops.size());
            ItemStack item = drops.get(0).getItem().copy();
            require(item.getItem() == block.asItem() && item.getCount() == 1
                    && WorksItemData.read(item).getString("FluidID").orElseThrow().equals("minecraft:water")
                    && WorksItemData.read(item).getInt("Volume").orElseThrow() == 500, "Native loot lost portable fluid NBT: " + item);
            drops.get(0).discard();
            world.setBlock(TANK, block.defaultBlockState(), 3);
            block.setPlacedBy(world, TANK, block.defaultBlockState(),
                    WorksOfflinePlayer.sentinel(world), item);
            require(fluid(world.getBlockEntity(TANK)).getFluidInTank(0).getAmount() == 500,
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
        net.minecraft.world.LockCode lock = new net.minecraft.world.LockCode(
                net.minecraft.advancements.predicates.ItemPredicate.Builder.item().of(
                        world.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ITEM),
                        net.minecraft.world.item.Items.STICK).build());
        state.put("lock", net.minecraft.world.LockCode.CODEC.encodeStart(
                world.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), lock).getOrThrow());
        WorksBlockData.load(chest, state);
        check(checks, "locked-chest/conveyor-refuses-extraction", () -> {
            require(((net.minecraft.world.level.block.entity.BaseContainerBlockEntity) chest).isLocked(),
                    "Vanilla did not install the native chest lock");
            require(InventoryLocks.isLocked(input), "Native nonempty chest lock was not detected");
            for (int i = 0; i < 160; i++) belt.tick();
            require(input.getItem(0).getCount() == 16 && output.isEmpty() && belt.isEmpty(),
                    "Conveyor bypassed locked source");
        });
        state.remove("lock");
        WorksBlockData.load(chest, state);
        check(checks, "unlocked-chest/conveyor-transfers-exactly", () -> {
            require(!InventoryLocks.isLocked(input), "Cleared lock still blocks extraction");
            for (int i = 0; i < 160; i++) belt.tick();
            require(input.isEmpty() && belt.isEmpty() && output.getItem(0).getCount() == 16
                    && output.getItem(0).getItem() == net.minecraft.world.item.Items.DIRT,
                    "Unlocked native chest did not deliver exactly sixteen dirt");
        });
    }

    private static final class CallbackTank extends StorageTankTileEntity {
        CallbackTank() { super(BlockPos.ZERO, zone.moddev.mc.basepower.init.Blocks.storage_tank.defaultBlockState()); }
        void reviveNativeCapabilities() { super.reviveCaps(); }
    }

    private static CallbackTank callbackTank() {
        require(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER != null
                && net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER != null,
                "Forge did not inject native fluid and item capability tokens");
        require((Object) net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER != net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER,
                "Native fluid and item capability tokens alias each other");
        CallbackTank tile = new CallbackTank();
        require(tile.getLevel() == null && !tile.isRemoved(), "Callback fixture is not a fresh standalone tank");
        cyano.poweradvantage.api.fluid.FluidStack contents =
                new cyano.poweradvantage.api.fluid.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 500);
        contents.tag = new CompoundTag();
        contents.tag.putString("InteropWitness", "native117-callback");
        tile.getTank().setFluid(contents);
        return tile;
    }

    private static void requireUnavailable(CallbackTank tile) {
        for (Direction face : Direction.values()) {
            require(!tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, face).isPresent()
                    && !tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, face).isPresent(),
                    "Unloaded callback tank recreated a sided capability: " + face);
        }
        require(!tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, null).isPresent()
                && !tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, null).isPresent(),
                "Unloaded callback tank recreated an unsided capability");
    }

    private static void capabilityCallbacks(JsonArray checks) {
        // These unplaced tanks witness native callbacks, not physical chunk unloading.
        check(checks, "capability/native-chunk-unloaded-callback", () -> {
            CallbackTank tile = callbackTank();
            LazyOptional<IFluidHandler> oldFluid = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, Direction.UP);
            LazyOptional<IItemHandler> oldItems = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, Direction.DOWN);
            require(oldFluid.isPresent() && oldItems.isPresent(), "Standalone tank lacks native capability wrappers");
            CompoundTag contents = tile.getTank().writeToNBT(new CompoundTag());
            tile.onChunkUnloaded();
            require(!tile.isRemoved(), "Native unload callback unexpectedly marked the standalone tank removed");
            require(!oldFluid.isPresent() && !oldItems.isPresent(), "Unload callback left old optionals valid");
            requireUnavailable(tile);
            require(contents.equals(tile.getTank().writeToNBT(new CompoundTag()))
                    && tile.getContainerSize() == 0 && tile.isEmpty(), "Unload callback changed tank contents");
        });
        check(checks, "capability/native-clear-removed-and-revive", () -> {
            for (boolean clearRemoved : new boolean[] {true, false}) {
                CallbackTank tile = callbackTank();
                LazyOptional<IFluidHandler> oldFluid = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, Direction.UP);
                LazyOptional<IItemHandler> oldItems = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, Direction.DOWN);
                IFluidHandler oldFluidWrapper = oldFluid.orElseThrow(() -> new AssertionError("Missing original fluid wrapper"));
                IItemHandler oldItemWrapper = oldItems.orElseThrow(() -> new AssertionError("Missing original item wrapper"));
                FluidStack before = oldFluidWrapper.getFluidInTank(0);
                require(before.getFluid() == net.minecraft.world.level.material.Fluids.WATER && before.getAmount() == 500
                        && before.hasTag() && before.getTag().getString("InteropWitness").orElseThrow().equals("native117-callback")
                        && oldItemWrapper.getSlots() == 0, "Standalone wrappers do not expose the seeded tank contents");
                CompoundTag contents = tile.getTank().writeToNBT(new CompoundTag());
                CompoundTag exposedFluid = before.writeToNBT(new CompoundTag());
                tile.onChunkUnloaded();
                require(!tile.isRemoved() && !oldFluid.isPresent() && !oldItems.isPresent(),
                        "Revival fixture was not invalidated while unremoved");
                requireUnavailable(tile);
                if (clearRemoved) tile.clearRemoved();
                else tile.reviveNativeCapabilities();
                LazyOptional<IFluidHandler> currentFluid = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, Direction.UP);
                LazyOptional<IItemHandler> currentItems = tile.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.ITEM_HANDLER, Direction.DOWN);
                IFluidHandler currentFluidWrapper = currentFluid.orElseThrow(() -> new AssertionError("Revival did not restore fluid capability"));
                IItemHandler currentItemWrapper = currentItems.orElseThrow(() -> new AssertionError("Revival did not restore item capability"));
                require(!tile.isRemoved() && currentFluid != oldFluid && currentItems != oldItems
                        && currentFluidWrapper != oldFluidWrapper && currentItemWrapper != oldItemWrapper,
                        "Native revival did not create fresh fluid and item wrappers; clearRemoved=" + clearRemoved);
                require(!oldFluid.isPresent() && !oldItems.isPresent(), "Native revival revalidated old optionals");
                require(contents.equals(tile.getTank().writeToNBT(new CompoundTag()))
                        && exposedFluid.equals(currentFluidWrapper.getFluidInTank(0).writeToNBT(new CompoundTag()))
                        && currentItemWrapper.getSlots() == 0 && tile.getContainerSize() == 0 && tile.isEmpty(),
                        "Native revival changed fluid identity, amount, tag or empty item contents");
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
            capabilityCallbacks(checks);
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
        if (net.minecraftforge.fml.ModList.isLoaded("basemetals")) {
            JsonArray shared = new JsonArray();
            check(shared, "basemetals/native-power-crushing", () -> sharedCrushing(world));
            report.add("sharedChecks", shared);
        }
        File destination = new File("advantage-works-results", "native-interop-" + phase + ".json");
        require(!destination.exists(), "Refusing to overwrite interop evidence");
        destination.getParentFile().mkdirs();
        Files.write(destination.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static void sharedCrushing(ServerLevel world) {
        Identifier recipeId = Identifier.fromNamespaceAndPath("basepower", "crushing_potato_starch");
        var holder = world.getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.RECIPE, recipeId)).orElseThrow(
                () -> new AssertionError("Missing Power recipe under Base Metals crushing: " + recipeId));
        var recipe = (net.minecraft.world.item.crafting.Recipe<net.minecraft.world.item.crafting.SingleRecipeInput>) holder.value();
        var type = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        require(Identifier.fromNamespaceAndPath("basemetals", "crushing").equals(type), "Wrong loaded crushing type: " + type);
        for (var potato : List.of(net.minecraft.world.item.Items.POTATO, net.minecraft.world.item.Items.POISONOUS_POTATO)) {
            var input = new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(potato, 3));
            require(recipe.matches(input, world), "Shared crusher rejected " + potato);
            ItemStack result = recipe.assemble(input);
            require(result.getCount() == 1 && Identifier.fromNamespaceAndPath("basepower", "starch").equals(
                    ForgeRegistries.ITEMS.getKey(result.getItem())), "Incorrect shared crushing result: " + result);
            require(input.getItem(0).getCount() == 3, "Assembling a recipe preview mutated its input");
        }
        require(!recipe.matches(new net.minecraft.world.item.crafting.SingleRecipeInput(new ItemStack(net.minecraft.world.item.Items.DIRT)), world),
                "Shared crushing accepted unrelated input");
    }
}
