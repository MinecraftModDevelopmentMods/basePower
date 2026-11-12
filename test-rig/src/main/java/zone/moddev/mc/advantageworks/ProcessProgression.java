package zone.moddev.mc.advantageworks;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.IAdvantageFluidHandler;
import cyano.poweradvantage.api.simple.TileEntitySimplePowerMachine;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.init.Fluids;
import zone.moddev.mc.basepower.machines.fluidmachines.StillTileEntity;
import zone.moddev.mc.basepower.util.FluidContainerHelper;
import static zone.moddev.mc.advantageworks.ProgressionProbe.*;

/** Non-table processing edges are verified with live bounded machines here. */
final class ProcessProgression {
    private static JsonArray evidence = new JsonArray();
    private static final java.util.Set<String> crusherWitnesses = new java.util.HashSet<>();
    static void reset() { evidence = new JsonArray(); crusherWitnesses.clear(); }
    static JsonArray witnesses() { return evidence; }

    static void smelt(ServerLevel world, JsonObject fixture, String recipe, ItemStack input, ItemStack expected) {
        FurnaceBlockEntity furnace = (FurnaceBlockEntity) fresh(world, fixture,
                new BlockPos(16394, 80, 16384), "minecraft:furnace");
        furnace.setItem(0, input.copy());
        furnace.setItem(1, stack("minecraft:coal"));
        for (int tick = 0; tick < 201; tick++) net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity.serverTick(
                world, furnace.getBlockPos(), furnace.getBlockState(), furnace);
        ItemStack output = furnace.getItem(2);
        require(furnace.getItem(0).isEmpty() && furnace.getItem(1).isEmpty()
                && !output.isEmpty() && id(output).equals(id(expected)) && output.getCount() == expected.getCount(),
                "Live furnace failed " + recipe + ": input=" + furnace.getItem(0)
                    + ", fuel=" + furnace.getItem(1) + ", output=" + output
                    + ", expected=" + expected + ", saved=" + WorksBlockData.save(furnace));
        record("live_furnace:" + recipe, input.getCount(), output.getCount());
    }

    static void hammer(ServerLevel world, ServerPlayer player, Map<String, JsonObject> known,
            JsonObject fixture, String recipe, ItemStack input, ItemStack expected) {
        BlockPos pos = new BlockPos(16396, 80, 16384);
        fresh(world, fixture, pos, "minecraft:stone");
        AABB area = new AABB(pos.above());
        for (ItemEntity entity : world.getEntitiesOfClass(ItemEntity.class, area)) {
            entity.setItem(ItemStack.EMPTY);
            entity.discard();
        }
        String hammer = known.keySet().stream().filter(key -> key.startsWith("basemetals:")
                && key.endsWith("_crackhammer")).findFirst().orElseThrow(() -> new IllegalStateException("No craftable hammer"));
        player.getInventory().clearContent();
        player.setItemInHand(InteractionHand.MAIN_HAND, stack(hammer));
        player.setShiftKeyDown(false);
        ItemEntity source = new ItemEntity(world, pos.getX() + 0.5D, pos.getY() + 1.1D, pos.getZ() + 0.5D, input.copy());
        boolean accepted = world.addFreshEntity(source);
        JsonObject attempt = new JsonObject();
        attempt.addProperty("protocol", "native117-hammer-query-v1");
        attempt.addProperty("recipe", recipe);
        attempt.addProperty("sourceUuid", source.getUUID().toString());
        attempt.addProperty("accepted", accepted);
        attempt.addProperty("inputNbt", stackNbt(world, input));
        attempt.addProperty("expectedNbt", stackNbt(world, expected));
        JsonObject before = hammerSnapshot(world, player, pos, area, source);
        attempt.add("before", before);
        attempt.addProperty("sourceMatchesInput", ItemStack.matches(source.getItem(), input));
        JsonObject selection = hammerRecipeSelection(world, player.getMainHandItem(), source.getItem(), recipe);
        attempt.add("baseSelection", selection);
        System.out.println("ADVHAMMER BEFORE " + attempt);
        require(HammerQueryGate.selectorObserved(selection),
                "Live hammer recipe-selection observer failed " + recipe);
        require(HammerQueryGate.ready(accepted, source.isRemoved(),
                attempt.get("sourceMatchesInput").getAsBoolean(), before),
                "Live hammer setup not query-ready " + recipe);
        InteractionResult action = player.getMainHandItem().getItem().useOn(
                new UseOnContext(player, InteractionHand.MAIN_HAND, new net.minecraft.world.phys.BlockHitResult(
                        new net.minecraft.world.phys.Vec3(pos.getX() + 0.5D, pos.getY() + 1D, pos.getZ() + 0.5D),
                        Direction.UP, pos, false)));
        attempt.addProperty("action", action == InteractionResult.CONSUME ? "CONSUME" : action.toString());
        attempt.addProperty("success", action == InteractionResult.CONSUME);
        attempt.addProperty("sourceConsumed", source.getItem().isEmpty());
        attempt.addProperty("damageIsOne", player.getMainHandItem().getDamageValue() == 1);
        attempt.add("after", hammerSnapshot(world, player, pos, area, source));
        System.out.println("ADVHAMMER AFTER " + attempt);
        require(action == InteractionResult.CONSUME && source.getItem().isEmpty()
                && player.getMainHandItem().getDamageValue() == 1, "Live hammer failed " + recipe);
        int produced = 0;
        for (ItemEntity entity : world.getEntitiesOfClass(ItemEntity.class, area)) {
            if (entity == source || entity.getItem().isEmpty()) continue;
            require(id(entity.getItem()).equals(id(expected)), "Unexpected hammer output for " + recipe
                    + ": " + entity.getItem() + ", expected " + expected);
            produced += entity.getItem().getCount();
            entity.setItem(ItemStack.EMPTY);
            entity.discard();
        }
        require(produced == expected.getCount(), "Hammer output count changed for " + recipe);
        record("live_hammer:" + recipe, input.getCount(), produced);
    }

    @SuppressWarnings("unchecked")
    private static JsonObject hammerRecipeSelection(ServerLevel world, ItemStack hammer, ItemStack input,
            String requestedRecipe) {
        JsonObject selection = new JsonObject();
        selection.addProperty("selector", "RecipeManager.getRecipeFor(basemetals:crushing)");
        selection.addProperty("inputNbt", stackNbt(world, input));
        selection.addProperty("recipeManagerIdentity", System.identityHashCode(world.recipeAccess()));
        selection.addProperty("requestedIdPresent",
                world.recipeAccess().byKey(RecipeUnlockProbe.recipeKey(requestedRecipe)).isPresent());
        try {
            Class<?> hammerClass = hammer.getItem().getClass();
            if (!hammerClass.getName().equals("zone.moddev.mc.basemetals.content.CrackhammerItem"))
                throw new IllegalStateException("Unexpected hammer class " + hammerClass.getName());
            RecipeType<?> type = net.minecraft.core.registries.BuiltInRegistries.RECIPE_TYPE
                    .getOptional(Identifier.fromNamespaceAndPath("basemetals", "crushing")).orElseThrow();
            var selected = world.recipeAccess().getRecipeFor(
                    (RecipeType<Recipe<net.minecraft.world.item.crafting.SingleRecipeInput>>) type,
                    new net.minecraft.world.item.crafting.SingleRecipeInput(input.copy()), world).orElse(null);
            selection.addProperty("status", selected == null ? "NO_MATCH" : "MATCH");
            selection.addProperty("matched", selected != null);
            if (selected != null) {
                selection.addProperty("id", selected.id().identifier().toString());
                selection.addProperty("class", selected.value().getClass().getName());
                selection.addProperty("resultNbt", stackNbt(world,
                        selected.value().assemble(new net.minecraft.world.item.crafting.SingleRecipeInput(input.copy()))));
            }
        } catch (RuntimeException | LinkageError error) {
            selection.addProperty("status", "OBSERVER_ERROR");
            selection.addProperty("errorType", error.getClass().getName());
            selection.addProperty("error", error.toString());
            if (error.getCause() != null) selection.addProperty("cause", error.getCause().toString());
        }
        return selection;
    }

    private static JsonObject hammerSnapshot(ServerLevel world, ServerPlayer player, BlockPos pos,
            AABB area, ItemEntity source) {
        JsonObject sample = new JsonObject();
        sample.addProperty("dimension", world.dimension().identifier().toString());
        sample.addProperty("gameTime", world.getGameTime());
        sample.addProperty("area", area.toString());
        sample.addProperty("sourceRemoved", source.isRemoved());
        sample.addProperty("sourceBounds", source.getBoundingBox().toString());
        sample.addProperty("sourceIntersectsArea", source.getBoundingBox().intersects(area));
        sample.addProperty("sourceNbt", stackNbt(world, source.getItem()));
        sample.addProperty("uuidLookupIsSource", world.getEntity(source.getUUID()) == source);
        sample.addProperty("entitiesLoaded", world.areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(pos)));
        sample.addProperty("blockAndEntitiesTicking", Native118ChunkTicking.withEntitiesLoaded(world, pos));
        sample.addProperty("entityTicking", world.isPositionEntityTicking(pos));
        sample.addProperty("clientSide", world.isClientSide());
        sample.addProperty("creative", player.isCreative());
        sample.addProperty("shift", player.isShiftKeyDown());
        sample.addProperty("hammerClass", player.getMainHandItem().getItem().getClass().getName());
        sample.addProperty("hammerNbt", stackNbt(world, player.getMainHandItem()));
        sample.addProperty("hammerDamage", player.getMainHandItem().getDamageValue());
        sample.addProperty("hammerMaxDamage", player.getMainHandItem().getMaxDamage());
        JsonArray items = new JsonArray();
        boolean sourceInAabb = false;
        for (ItemEntity entity : world.getEntitiesOfClass(ItemEntity.class, area)) {
            sourceInAabb |= entity == source;
            JsonObject item = new JsonObject();
            item.addProperty("uuid", entity.getUUID().toString());
            item.addProperty("isSource", entity == source);
            item.addProperty("removed", entity.isRemoved());
            item.addProperty("bounds", entity.getBoundingBox().toString());
            item.addProperty("stackNbt", stackNbt(world, entity.getItem()));
            items.add(item);
        }
        sample.addProperty("sourceInAabb", sourceInAabb);
        sample.add("items", items);
        return sample;
    }

    private static String stackNbt(ServerLevel world, ItemStack stack) {
        return ItemStack.OPTIONAL_CODEC.encodeStart(
                world.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack)
                .getOrThrow().toString();
    }

    static boolean canCrush(Map<String, JsonObject> known, ItemStack input) {
        if (input.getItem() instanceof BlockItem) {
            for (String id : known.keySet()) {
                if (id.startsWith("basemetals:") && id.endsWith("_crackhammer")
                        && stack(id).isCorrectToolForDrops(((BlockItem) input.getItem()).getBlock().defaultBlockState())) return true;
            }
            return (has(known, "basesteam:steam_crusher", "basesteam:steam_boiler_coal", "minecraft:water_bucket"))
                    || has(known, "baseelectric:electric_crusher", "baseelectric:hydroelectric_generator", "minecraft:water_bucket");
        }
        return true;
    }

    static boolean advance(ServerLevel world, ServerPlayer player, Map<String, JsonObject> known, JsonObject fixture) {
        boolean changed = false;
        for (String machine : new String[] {"basesteam:steam_crusher", "baseelectric:electric_crusher"}) {
            if (known.containsKey(machine) && crusherWitnesses.add(machine)) {
                for (String potato : new String[] {"minecraft:potato", "minecraft:poisonous_potato"})
                    crushPotato(world, fixture, machine, potato);
            }
        }
        if (known.containsKey("baseelectric:sulfur_ore") && !known.containsKey("baseelectric:sulfur_powder")) {
            BlockPos pos = new BlockPos(16392, 80, 16384);
            fresh(world, fixture, pos, "baseelectric:sulfur_ore");
            java.util.List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(
                    world.getBlockState(pos), world, pos, world.getBlockEntity(pos));
            require(drops.size() == 1 && id(drops.get(0)).equals("baseelectric:sulfur_powder")
                    && drops.get(0).getCount() >= 1 && drops.get(0).getCount() <= 4, "Sulfur ore drops changed");
            changed |= acquire(known, player, drops.get(0), "mine_generated_sulfur_ore", Collections.singletonList("baseelectric:sulfur_ore"));
        }
        if (known.containsKey("minecraft:bucket")) {
            for (String fluid : new String[] {"minecraft:water", "basepower:crude_oil"}) {
                String bucket = fluid.equals("minecraft:water") ? "minecraft:water_bucket" : fluid + "_bucket";
                if (known.containsKey(bucket)) continue;
                BlockPos pos = new BlockPos(16390, 80, 16384);
                fresh(world, fixture, pos, fluid);
                player.getInventory().clearContent();
                player.absSnapTo(pos.getX() + 0.5D, pos.getY() + 2, pos.getZ() + 0.5D, 0, 90);
                player.setItemInHand(InteractionHand.MAIN_HAND, stack("minecraft:bucket"));
                InteractionResult action = player.getMainHandItem().getItem().use(world, player, InteractionHand.MAIN_HAND);
                require(action instanceof InteractionResult.Success, "Native world-fluid pickup failed: " + fluid);
                ItemStack output = ((InteractionResult.Success) action).heldItemTransformedTo();
                require(output != null, "Native world-fluid pickup did not return a filled bucket: " + fluid);
                require(!output.isEmpty() && id(output).equals(bucket), "Native world-fluid pickup changed identity: " + fluid);
                require(world.getFluidState(pos).isEmpty(), "Bucket pickup left its source behind");
                changed |= acquire(known, player, output, "bucket_collect:" + fluid, Collections.singletonList("minecraft:bucket"));
                record("bucket_collect:" + fluid, 1000, 1);
            }
        }
        if (!known.containsKey("basepower:refined_oil_bucket") && has(known,
                "basepower:still", "basepower:crude_oil_bucket", "minecraft:bucket", "minecraft:coal")) {
            StillTileEntity still = (StillTileEntity) fresh(world, fixture, new BlockPos(16386, 80, 16384), "basepower:still");
            ((Container) still).setItem(0, stack("minecraft:coal"));
            still.powerUpdate();
            for (int batch = 0; batch < 2; batch++) {
                require(still.getInputTank().fill(new FluidStack(Fluids.crude_oil, 1000), true) == 1000, "Still rejected crude oil");
                for (int tick = 0; tick < 502; tick++) still.tickUpdate(true);
                require(still.getInputTank().getFluidAmount() == 0 && still.getTank().getFluidAmount() == (batch + 1) * 500,
                        "Distillation did not conserve its 2:1 ratio");
            }
            player.getInventory().clearContent();
            player.setItemInHand(InteractionHand.MAIN_HAND, stack("minecraft:bucket"));
            require(FluidContainerHelper.interact(player, InteractionHand.MAIN_HAND, still, null), "Cannot collect refined oil from still");
            ItemStack output = player.getMainHandItem().copy();
            require(id(output).equals("basepower:refined_oil_bucket") && still.getTank().getFluidAmount() == 0,
                    "Refined-oil bucket changed identity or duplicated fluid");
            changed |= acquire(known, player, output, "live_distillation:2000mB_to_1000mB",
                    Arrays.asList("basepower:still", "basepower:crude_oil_bucket", "minecraft:coal", "minecraft:bucket"));
            record("live_distillation", 2000, 1000);
        }
        if (!known.containsKey("baseelectric:petrolplastic_ingot") && has(known,
                "baseelectric:plastic_refinery", "basepower:refined_oil_bucket")
                && (known.containsKey("baseelectric:hydroelectric_generator")
                    || known.containsKey("baseelectric:photovoltaic_generator")
                    || known.containsKey("baseelectric:steam_powered_generator"))) {
            BlockEntity tile = fresh(world, fixture, new BlockPos(16388, 80, 16384), "baseelectric:plastic_refinery");
            IAdvantageFluidHandler fluids = (IAdvantageFluidHandler) tile;
            require(fluids.fill(null, new FluidStack(Fluids.refined_oil, 100), true) == 100, "Refinery rejected refined oil");
            TileEntitySimplePowerMachine machine = (TileEntitySimplePowerMachine) tile;
            ConduitType electricity = new ConduitType("electricity");
            float supplied = 0;
            for (int tick = 0; tick < 101; tick++) {
                supplied += machine.addEnergy(500, electricity);
                machine.tickUpdate(true);
            }
            ItemStack output;
            try { output = (ItemStack) tile.getClass().getMethod("getOutputSlot", int.class).invoke(tile, 0); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            require(output != null && !output.isEmpty() && id(output).equals("baseelectric:petrolplastic_ingot") && output.getCount() == 1,
                    "Refinery did not make exactly one plastic ingot");
            require(fluids.getTankInfo(null)[0].fluid == null || fluids.getTankInfo(null)[0].fluid.amount == 0, "Refinery did not consume 100mB");
            require(Math.abs(supplied - machine.getEnergy(electricity) - 1200) < 0.001F, "Refinery electricity consumption changed");
            changed |= acquire(known, player, output, "live_refinery:100mB_1200energy",
                    Arrays.asList("baseelectric:plastic_refinery", "basepower:refined_oil_bucket", "baseelectric:hydroelectric_generator"));
            record("live_refinery", 100, 1);
        }
        return changed;
    }

    private static void crushPotato(ServerLevel world, JsonObject fixture, String id, String potato) {
        boolean steam = id.startsWith("basesteam:");
        TileEntitySimplePowerMachine machine = (TileEntitySimplePowerMachine) fresh(world, fixture,
                new BlockPos(steam ? 16400 : 16402, 80, 16384), id);
        ItemStack input = stack(potato);
        input.setCount(2);
        Container inventory = (Container) machine;
        inventory.setItem(0, input);
        machine.powerUpdate();
        ConduitType power = new ConduitType(steam ? "steam" : "electricity");
        float supplied = 0;
        int ticks = steam ? 125 : 200;
        for (int tick = 0; tick < ticks; tick++) {
            supplied += machine.addEnergy(500, power);
            machine.tickUpdate(true);
        }
        ItemStack output;
        try { output = steam ? inventory.getItem(1)
                : (ItemStack) machine.getClass().getMethod("getOutputSlot", int.class).invoke(machine, 0); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        require(output != null && !output.isEmpty() && id(output).equals("basepower:starch") && output.getCount() == 1,
                "Powered crusher did not produce one starch: " + id + "/" + potato);
        require(inventory.getItem(0).getCount() == 1, "Powered crusher did not consume exactly one potato");
        require(supplied > machine.getEnergy(power), "Powered crusher did not consume energy");
        record("live_crusher:" + id + ":" + potato, 1, output.getCount());
    }

    private static boolean has(Map<String, JsonObject> known, String... ids) {
        for (String id : ids) if (!known.containsKey(id)) return false;
        return true;
    }

    private static BlockEntity fresh(ServerLevel world, JsonObject fixture, BlockPos pos, String block) {
        JsonObject bounds = fixture.getAsJsonObject("machineBounds");
        int[] p = {pos.getX(), pos.getY(), pos.getZ()};
        for (int i = 0; i < 3; i++) require(p[i] >= bounds.getAsJsonArray("min").get(i).getAsInt()
                && p[i] <= bounds.getAsJsonArray("max").get(i).getAsInt(), "Machine fixture escaped its bounds");
        world.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        world.removeBlockEntity(pos);
        net.minecraft.world.level.block.Block material = ForgeRegistries.BLOCKS.getValue(Identifier.parse(block));
        require(material != null, "Missing machine/fluid " + block);
        world.setBlockAndUpdate(pos, material.defaultBlockState());
        BlockEntity clean = world.getBlockEntity(pos);
        require(!material.defaultBlockState().hasBlockEntity() || clean != null, "Native factory omitted the machine");
        if (clean != null) require(clean.getBlockPos().equals(pos)
                && clean.getBlockState().equals(world.getBlockState(pos)), "Native factory returned stale placement context");
        return clean;
    }

    private static void record(String operation, int input, int output) {
        JsonObject row = new JsonObject();
        row.addProperty("operation", operation);
        row.addProperty("input", input);
        row.addProperty("output", output);
        row.addProperty("status", "PASS");
        evidence.add(row);
    }
}
