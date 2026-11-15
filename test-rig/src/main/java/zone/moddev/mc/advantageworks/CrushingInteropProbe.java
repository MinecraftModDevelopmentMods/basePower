package zone.moddev.mc.advantageworks;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import zone.moddev.mc.basepower.compat.CrushingRecipes;
import zone.moddev.mc.lib.api.MaterialEventContexts.EntityTarget;
import zone.moddev.mc.lib.api.MaterialEventContexts.ToolTarget;
import zone.moddev.mc.lib.common.recipes.CrushingRecipe;
import zone.moddev.mc.lib.common.runtime.CrackhammerEvents;

/** Real dropped-item crushing and natural machine ticks in a bounded, marked-world station. */
final class CrushingInteropProbe {
    private static final File MARKER = new File("advantage-native-interop.allow");
    private static final File RESULTS = new File("advantage-works-results");
    private static final BlockPos ORIGIN = new BlockPos(1100, 8, 1100);
    private static final String ENTITY_TAG = "advantage-crushing-interop";
    private static final int PREPARE_LIMIT = 200;
    private static final int MEASURE_TICKS = 400;
    private static final float EPSILON = 0.01F;
    private Run active;

    static void register() {
        if (!MARKER.isFile()) return;
        CrushingInteropProbe probe = new CrushingInteropProbe();
        NeoForge.EVENT_BUS.addListener(probe::commands);
        WorksLevelTick.listen(NeoForge.EVENT_BUS, probe::tick);
    }

    private void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advcrushing").requires(source -> source.hasPermission(4))
                .then(Commands.argument("label", StringArgumentType.word())
                        .then(Commands.argument("expected-count", IntegerArgumentType.integer(0, 64)).executes(context -> {
                            start(context.getSource().getLevel(), StringArgumentType.getString(context, "label"),
                                    IntegerArgumentType.getInteger(context, "expected-count"));
                            return 1;
                        }))));
    }

    private void start(ServerLevel world, String label, int expected) {
        require(active == null, "A crushing run is already active");
        require(MARKER.isFile() && world.dimension() == Level.OVERWORLD
                && world.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration"), "Unmarked or wrong world");
        require(label.matches("[A-Za-z0-9_-]{1,64}"), "Unsafe crushing label");
        require(!new File("advantage-machine-operation.allow").exists(),
                "Use a server started without advantage-machine-operation.allow; that campaign globally holds native tickers");
        require(!result(label).exists() && !new File(RESULTS, "crushing-interop-" + label + "-start.json").exists(),
                "Choose a new label; crushing evidence must not be overwritten");
        active = new Run(world, label, expected);
        try {
            write(new File(RESULTS, "crushing-interop-" + label + "-start.json"), active.report);
            active.forceChunks();
            System.out.println("ADVCRUSHING START " + label + " expected=" + expected);
        } catch (Throwable failure) { finish(failure); }
    }

    private void tick(WorksLevelTick event) {
        Run run = active;
        if (run == null || event.level != run.world) return;
        try {
            require(MARKER.isFile(), "Crushing marker was removed during the run");
            if (!run.prepared) {
                if (event.phase != WorksLevelTick.Phase.END) return;
                require(run.world.getGameTime() - run.requested <= PREPARE_LIMIT, "Crushing chunks did not become entity-ticking in time");
                if (!run.ticking()) return;
                run.prepare();
            } else if (event.phase == WorksLevelTick.Phase.START) {
                run.beforeTick();
            } else {
                run.afterTick();
                if (run.measured >= MEASURE_TICKS) finish(null);
            }
        } catch (Throwable failure) { finish(failure); }
    }

    private void finish(Throwable failure) {
        Run run = active;
        if (run == null) return;
        try {
            if (failure == null) {
                try { run.verify(); } catch (Throwable error) { failure = error; }
            }
            run.report.addProperty("status", failure == null ? "PASS" : "FAIL");
            run.report.addProperty("finishedUtc", Instant.now().toString());
            run.report.addProperty("finishedWorldTime", run.world.getGameTime());
            run.report.addProperty("measuredTicks", run.measured);
            if (failure != null) {
                run.report.addProperty("failure", failure.toString());
                java.io.StringWriter trace = new java.io.StringWriter();
                failure.printStackTrace(new java.io.PrintWriter(trace));
                run.report.addProperty("traceback", trace.toString());
            }
            run.report.add("stationsAfter", run.snapshots());
            write(result(run.label), run.report);
            System.out.println("ADVCRUSHING COMPLETE " + run.label + " status=" + (failure == null ? "PASS" : "FAIL"));
        } catch (Exception error) {
            System.err.println("ADVCRUSHING RECEIPT_FAILURE " + run.label + " " + error);
            throw new IllegalStateException("Could not preserve crushing receipt for " + run.label, error);
        } finally {
            run.releaseChunks();
            active = null;
        }
    }

    private static final class Run {
        final ServerLevel world;
        final String label;
        final int expectedCount;
        final long requested;
        final JsonObject report = new JsonObject();
        final List<ChunkPos> ownedTickets = new ArrayList<>();
        final List<Pair> pairs = new ArrayList<>();
        final ItemStack input = new ItemStack(Items.POTATO);
        ItemStack expected;
        ItemEntity dropped;
        int measured;
        boolean prepared;
        boolean sampled;

        Run(ServerLevel world, String label, int expectedCount) {
            this.world = world;
            this.label = label;
            this.expectedCount = expectedCount;
            requested = world.getGameTime();
            WorksItemData.update(input, tag -> tag.putString("CrushingInputWitness", label));
            report.addProperty("label", label);
            report.addProperty("expectedCount", expectedCount);
            report.addProperty("status", "STARTED");
            report.addProperty("startedUtc", Instant.now().toString());
            report.addProperty("processId", ProcessHandle.current().pid());
            report.addProperty("processStartedUtc", ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown"));
            report.addProperty("worldTime", requested);
            report.addProperty("dimension", world.dimension().location().toString());
            report.add("input", stack(input, world));
            report.addProperty("scope", "Actual MMDLib public tool handler and naturally ticked machines; parent sequences reloads and cold restarts");
            JsonObject versions = new JsonObject();
            for (String id : List.of("mmdlib", "basepower", "basesteam", "baseelectric")) {
                versions.addProperty(id, ModList.get().getModContainerById(id).orElseThrow().getModInfo().getVersion().toString());
            }
            report.add("versions", versions);
        }

        void forceChunks() {
            for (int x = ORIGIN.getX() >> 4; x <= (ORIGIN.getX() + 14) >> 4; x++) {
                for (int z = ORIGIN.getZ() >> 4; z <= (ORIGIN.getZ() + 2) >> 4; z++) {
                    if (!world.getForcedChunks().contains(ChunkPos.asLong(x, z))) {
                        ownedTickets.add(new ChunkPos(x, z));
                        world.setChunkForced(x, z, true);
                    }
                }
            }
        }

        void releaseChunks() {
            for (ChunkPos chunk : ownedTickets) world.setChunkForced(chunk.x, chunk.z, false);
        }

        boolean ticking() {
            for (BlockPos pos : List.of(ORIGIN, ORIGIN.offset(4, 0, 0), ORIGIN.offset(12, 0, 0))) {
                if (!Native118ChunkTicking.withEntitiesLoaded(world, pos) || !world.isPositionEntityTicking(pos)) return false;
            }
            return true;
        }

        void prepare() {
            require(ticking(), "Station must be naturally entity-ticking");
            expected = CrushingRecipes.find(world, input);
            require((expected == null ? 0 : expected.getCount()) == expectedCount, "Shared lookup does not match the requested datapack phase");
            require(ItemStack.matches(input, inputWitness()), "Shared preview mutated the input");
            report.add("sharedResult", stack(expected, world));
            report.addProperty("recipeId", world.getRecipeManager().getRecipeFor(CrushingRecipe.TYPE.get(),
                    new SingleRecipeInput(input.copy()), world).map(holder -> holder.id().toString()).orElse("absent"));
            report.add("previousStations", snapshotsAt(List.of(ORIGIN.offset(3, 0, 0), ORIGIN.offset(4, 0, 0),
                    ORIGIN.offset(11, 0, 0), ORIGIN.offset(12, 0, 0))));
            hammer();
            pairs.add(new Pair(this, "steam", ORIGIN.offset(4, 0, 0), "basesteam:steam_crusher",
                    "basesteam:steam_tank", 1, 125, 1.5F, 1000, 50));
            pairs.add(new Pair(this, "electricity", ORIGIN.offset(12, 0, 0), "baseelectric:electric_crusher",
                    "baseelectric:electric_battery_array", 3, 200, 12, 6000, 500));
            report.add("stationsBefore", snapshots());
            report.addProperty("measurementStartedWorldTime", world.getGameTime());
            prepared = true;
        }

        ItemStack inputWitness() {
            ItemStack original = new ItemStack(Items.POTATO);
            WorksItemData.update(original, tag -> tag.putString("CrushingInputWitness", label));
            return original;
        }

        void hammer() {
            AABB area = hammerArea();
            for (ItemEntity entity : world.getEntitiesOfClass(ItemEntity.class, area)) {
                require(entity.getTags().contains(ENTITY_TAG), "Unowned dropped item occupies the hammer station");
                entity.discard();
            }
            place(world, ORIGIN.below(), net.minecraft.world.level.block.Blocks.BEDROCK);
            ItemStack hammer = new ItemStack(item("basemetals:iron_crackhammer"));
            require(hammer.isDamageableItem() && hammer.getMaxDamage() > 1, "Use the real registered, damageable Base Metals hammer");
            WorksOfflinePlayer player = new WorksOfflinePlayer(world, new GameProfile(
                    UUID.nameUUIDFromBytes(("advcrushing-" + label).getBytes(StandardCharsets.UTF_8)), "AdvCrushing"));
            player.getAbilities().instabuild = false;
            player.setPos(ORIGIN.getX() + 0.5, ORIGIN.getY(), ORIGIN.getZ() + 0.5);
            player.setItemInHand(InteractionHand.MAIN_HAND, hammer);
            dropped = new ItemEntity(world, ORIGIN.getX() + 0.5, ORIGIN.getY() + 0.5, ORIGIN.getZ() + 0.5, input.copy());
            dropped.setNoGravity(true);
            dropped.setDeltaMovement(Vec3.ZERO);
            dropped.setNeverPickUp();
            dropped.addTag(ENTITY_TAG);
            require(world.addFreshEntity(dropped), "Could not install the live dropped potato");
            int damage = hammer.getDamageValue();
            ToolTarget target = new EntityTarget(dropped, InteractionHand.MAIN_HAND, hammer);
            CrackhammerEvents.crush(player, target);
            JsonArray outputs = new JsonArray();
            int actual = 0;
            for (ItemEntity entity : world.getEntitiesOfClass(ItemEntity.class, area)) {
                entity.addTag(ENTITY_TAG);
                if (entity == dropped) continue;
                require(expected != null && ItemStack.isSameItemSameComponents(expected, entity.getItem()),
                        "Hammer produced an unrelated item or lost result components");
                actual += entity.getItem().getCount();
                outputs.add(stack(entity.getItem(), world));
            }
            require(actual == expectedCount, "Hammer output differs from the shared recipe");
            require(expectedCount == 0 ? dropped.isAlive() && ItemStack.matches(dropped.getItem(), input)
                    : dropped.isRemoved(), "Hammer did not consume exactly one input, or consumed a blocked input");
            require(hammer.getDamageValue() - damage == (expectedCount == 0 ? 0 : 1), "Incorrect hammer durability debit");
            JsonObject proof = new JsonObject();
            proof.addProperty("handler", "CrackhammerEvents.crush(Player, EntityTarget)");
            proof.addProperty("item", BuiltInRegistries.ITEM.getKey(hammer.getItem()).toString());
            proof.addProperty("outputCount", actual);
            proof.addProperty("inputRemaining", expectedCount == 0 ? dropped.getItem().getCount() : 0);
            proof.addProperty("durabilityDebit", hammer.getDamageValue() - damage);
            proof.add("outputs", outputs);
            report.add("hammer", proof);
        }

        void beforeTick() {
            require(ticking(), "Station stopped ticking during the measurement");
            for (Pair pair : pairs) pair.before = pair.energy();
            sampled = true;
        }

        void afterTick() {
            require(sampled, "Missing before-tick energy sample");
            sampled = false;
            ItemStack live = CrushingRecipes.find(world, input);
            require(same(expected, live), "Recipe changed during a labeled run; wait for completion before /reload");
            require(ItemStack.matches(input, inputWitness()), "Shared lookup mutated the input witness");
            for (Pair pair : pairs) pair.sample();
            measured++;
        }

        void verify() {
            require(prepared && measured == MEASURE_TICKS, "Incomplete bounded machine observation");
            JsonArray machines = new JsonArray();
            for (Pair pair : pairs) machines.add(pair.verify());
            report.add("machines", machines);
            if (expectedCount == 0) require(dropped.isAlive() && ItemStack.matches(dropped.getItem(), input),
                    "Blocked hammer input was lost during the observation window");
        }

        JsonArray snapshots() {
            List<BlockPos> positions = new ArrayList<>();
            for (Pair pair : pairs) { positions.add(pair.pos.west()); positions.add(pair.pos); }
            return snapshotsAt(positions);
        }

        JsonArray snapshotsAt(List<BlockPos> positions) {
            JsonArray values = new JsonArray();
            for (BlockPos pos : positions) {
                JsonObject row = new JsonObject();
                row.addProperty("position", pos.toShortString());
                row.addProperty("block", BuiltInRegistries.BLOCK.getKey(world.getBlockState(pos).getBlock()).toString());
                BlockEntity tile = world.getBlockEntity(pos);
                if (tile != null) row.addProperty("saved", tile.saveWithFullMetadata(world.registryAccess()).toString());
                values.add(row);
            }
            return values;
        }
    }

    private static final class Pair {
        final Run run;
        final BlockPos pos;
        final String name;
        final ConduitType power;
        final PoweredEntity machine;
        final PoweredEntity source;
        final Container inventory;
        final int inputs;
        final int requiredWork;
        final float rate;
        final float initialEnergy;
        float before;
        float idleDebit;
        int previousProgress;
        int previousInput = 1;
        int work;
        int outputCount;

        Pair(Run run, String name, BlockPos pos, String machineId, String sourceId,
                int inputs, int requiredWork, float rate, float sourceReserve, float machineReserve) {
            this.run = run;
            this.name = name;
            this.pos = pos;
            this.inputs = inputs;
            this.requiredWork = requiredWork;
            this.rate = rate;
            power = new ConduitType(name);
            for (BlockPos cell : List.of(pos, pos.west())) {
                place(run.world, cell.below(), net.minecraft.world.level.block.Blocks.BEDROCK);
                for (net.minecraft.core.Direction side : net.minecraft.core.Direction.values()) {
                    BlockPos adjacent = cell.relative(side);
                    if (!adjacent.equals(pos) && !adjacent.equals(pos.west())) {
                        require(run.world.getBlockEntity(adjacent) == null, "Foreign block entity neighbours a crushing fixture");
                        var neighbour = run.world.getBlockState(adjacent);
                        require(neighbour.isAir() || neighbour.is(net.minecraft.world.level.block.Blocks.BEDROCK),
                                "Crushing fixture is not isolated from neighbouring blocks or power conduits");
                    }
                }
            }
            place(run.world, pos, block(machineId));
            place(run.world, pos.west(), block(sourceId));
            require(run.world.getBlockEntity(pos) instanceof PoweredEntity
                    && run.world.getBlockEntity(pos.west()) instanceof PoweredEntity, "Missing real powered fixtures");
            machine = (PoweredEntity) run.world.getBlockEntity(pos);
            source = (PoweredEntity) run.world.getBlockEntity(pos.west());
            inventory = (Container) machine;
            if (name.equals("electricity")) {
                ((Container) source).setItem(0, new ItemStack(item("baseelectric:lithium_battery")));
            }
            require(source.getEnergyCapacity(power) >= sourceReserve && machine.getEnergyCapacity(power) >= machineReserve,
                    "Finite reserve exceeds native capacity");
            source.setEnergy(sourceReserve, power);
            machine.setEnergy(machineReserve, power);
            source.setChanged();
            machine.setChanged();
            initialEnergy = energy();
            require(Math.abs(initialEnergy - sourceReserve - machineReserve) <= EPSILON, "Finite energy reserve was not installed exactly");
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) require(inventory.getItem(slot).isEmpty(), "Fixture inventory was not fresh");
            inventory.setItem(0, run.input.copy());
            inventory.setChanged();
            require(!run.world.hasNeighborSignal(pos) && !run.world.hasNeighborSignal(pos.west()), "Fixture is redstone-disabled");
        }

        float energy() { return machine.getEnergy(power) + source.getEnergy(power); }

        void sample() {
            require(run.world.getBlockEntity(pos) == machine && run.world.getBlockEntity(pos.west()) == source,
                    "Crushing actors were replaced during the native measurement");
            int count = 0;
            for (int slot = 0; slot < inputs; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty()) require(slot == 0 && ItemStack.isSameItemSameComponents(stack, run.input), "Input identity or components changed");
                count += stack.getCount();
            }
            require(count >= 0 && count <= previousInput, "Machine duplicated its input");
            CompoundTag saved = machine.saveWithoutMetadata(run.world.registryAccess());
            int progress = name.equals("steam") ? saved.getShort("progress") : saved.getList("smashTime", Tag.TAG_SHORT).getShort(0);
            int consumed = previousInput - count;
            int delta = consumed == 0 ? progress - previousProgress : requiredWork - previousProgress + progress;
            require(progress >= 0 && progress < requiredWork && delta >= 0 && delta <= 1, "Invalid or duplicate native work tick");
            require(count != 0 || progress == 0, "Machine keeps working after its only input was consumed");
            work += delta;
            float debit = before - energy();
            float idle = debit - rate * delta;
            float limit = name.equals("steam") ? 2 * 0.0625F : 0;
            require(Float.isFinite(debit) && idle >= -EPSILON && idle <= limit + EPSILON,
                    "Energy is not conserved: " + name + " debit=" + debit + " work=" + delta + " idle=" + idle);
            idleDebit += idle;
            int output = 0;
            for (int slot = inputs; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty()) continue;
                require(run.expected != null && ItemStack.isSameItemSameComponents(stack, run.expected), "Machine lost output identity or components");
                output += stack.getCount();
            }
            require(output == (1 - count) * run.expectedCount, "Machine output does not equal exactly one recipe per input");
            outputCount = output;
            if (run.expectedCount == 0) require(count == 1 && progress == 0 && work == 0, "Removed recipe consumed input or performed productive work");
            previousInput = count;
            previousProgress = progress;
        }

        JsonObject verify() {
            require(previousInput == (run.expectedCount == 0 ? 1 : 0), "Machine did not complete the expected physical operation: " + name);
            require(work == (run.expectedCount == 0 ? 0 : requiredWork), "Wrong finite work count: " + name);
            float debit = initialEnergy - energy();
            require(energy() >= 0 && Math.abs(debit - work * rate - idleDebit) <= EPSILON,
                    "Whole-run energy conservation failed: " + name);
            if (name.equals("electricity")) require(Math.abs(debit - work * rate) <= EPSILON, "Electric crusher did not debit its exact productive cost");
            if (name.equals("steam") && run.expectedCount == 0)
                require(Math.abs(idleDebit - run.measured * 2 * 0.0625F) <= EPSILON,
                        "Blocked Steam proof must still witness both actors' natural idle ticks");
            if (name.equals("electricity")) {
                var state = run.world.getBlockState(pos);
                var powered = state.getProperties().stream().filter(property -> property.getName().equals("powered"))
                        .findFirst().orElseThrow(() -> new AssertionError("Missing native powered-state witness"));
                require(Boolean.TRUE.equals(state.getValue(powered)),
                        "Finite power was not acknowledged by the naturally ticked electric machine");
            }
            JsonObject proof = new JsonObject();
            proof.addProperty("machine", name);
            proof.addProperty("inputConsumed", 1 - previousInput);
            proof.addProperty("outputCount", outputCount);
            proof.addProperty("workTicks", work);
            proof.addProperty("energyBefore", initialEnergy);
            proof.addProperty("energyAfter", energy());
            proof.addProperty("productiveEnergy", work * rate);
            proof.addProperty("observedIdleEnergy", idleDebit);
            return proof;
        }
    }

    private static AABB hammerArea() { return new AABB(ORIGIN).inflate(2); }

    private static Block block(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        require(BuiltInRegistries.BLOCK.containsKey(key), "Missing block " + id);
        return BuiltInRegistries.BLOCK.get(key);
    }

    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.parse(id);
        require(BuiltInRegistries.ITEM.containsKey(key), "Missing item " + id);
        return BuiltInRegistries.ITEM.get(key);
    }

    private static void place(ServerLevel world, BlockPos pos, Block block) {
        require(pos.getX() >= 1099 && pos.getX() <= 1113 && pos.getY() >= 7 && pos.getY() <= 8
                && pos.getZ() >= 1099 && pos.getZ() <= 1101, "Outside isolated crushing fixture bounds");
        BlockEntity previous = world.getBlockEntity(pos);
        if (previous != null) require(previous instanceof PoweredEntity
                && world.getBlockState(pos).is(block), "Refusing to replace an unrelated block entity");
        AABB area = new AABB(pos).inflate(2);
        Set<UUID> existingItems = new HashSet<>();
        for (ItemEntity item : world.getEntitiesOfClass(ItemEntity.class, area)) existingItems.add(item.getUUID());
        world.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3);
        require(world.setBlock(pos, block.defaultBlockState(), 3), "Could not place crushing fixture " + pos);
        for (ItemEntity item : world.getEntitiesOfClass(ItemEntity.class, area))
            if (!existingItems.contains(item.getUUID())) item.addTag(ENTITY_TAG);
    }

    private static JsonObject stack(ItemStack stack, ServerLevel world) {
        JsonObject value = new JsonObject();
        boolean empty = stack == null || stack.isEmpty();
        value.addProperty("count", empty ? 0 : stack.getCount());
        if (!empty) {
            value.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            value.addProperty("saved", stack.save(world.registryAccess()).toString());
        }
        return value;
    }

    private static boolean same(ItemStack left, ItemStack right) {
        return left == null ? right == null : right != null && ItemStack.matches(left, right);
    }

    private static File result(String label) { return new File(RESULTS, "crushing-interop-" + label + ".json"); }

    private static void write(File file, JsonObject value) throws Exception {
        Files.createDirectories(RESULTS.toPath());
        Files.writeString(file.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(value),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
