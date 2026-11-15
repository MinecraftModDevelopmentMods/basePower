package zone.moddev.mc.advantageworks.integration;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import zone.moddev.mc.basepower.machines.fluidmachines.StorageTankTileEntity;
import zone.moddev.mc.basepower.registry.MachineGUIRegistry;

/** Marked real integrated-server flow; deliberately separate from the worldless resource probe. */
@Mod("advantage_client_integration")
public final class NativeClientIntegration {
    private static final BlockPos POS = new BlockPos(50004, 90, 50004);
    private final long started = System.nanoTime();
    private JsonObject marker, result;
    private Path root, output;
    private String phase;
    private int state, age;
    private CompletableFuture<JsonObject> pending;
    private int lastState = -1;

    public NativeClientIntegration() {
        if (Boolean.getBoolean("advantageworks.clientIntegration")) NeoForge.EVENT_BUS.addListener(this::tick);
    }

    private void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (state == 99) return;
            if (root == null) root = mc.gameDirectory.toPath().toRealPath();
            if (lastState != state) {
                com.mojang.logging.LogUtils.getLogger().info("NATIVE_CLIENT_INTEGRATION state={} screen={}", state,
                        mc.screen == null ? "null" : mc.screen.getClass().getName());
                lastState = state;
            }
            if ((System.nanoTime() - started) / 1_000_000_000L > 180) throw new IllegalStateException("Native integration timed out at state " + state + " screen=" + mc.screen);
            if (state == 0) {
                if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) return;
                root = mc.gameDirectory.toPath().toRealPath();
                marker = JsonParser.parseString(Files.readString(root.resolve("client-integration.allow.json"))).getAsJsonObject();
                require(marker.get("schema").getAsInt() == 1 && marker.get("disposable").getAsBoolean(), "Unmarked integration directory");
                phase = marker.get("phase").getAsString();
                require(phase.equals("initial") || phase.equals("reload"), "Unknown integration phase");
                output = root.resolve("client-integration-" + phase + ".json");
                require(!Files.exists(output), "Refusing to replace an integration receipt");
                verifyInputs();
                result = new JsonObject();
                result.addProperty("status", "RUNNING");
                result.addProperty("phase", phase);
                result.addProperty("pid", ProcessHandle.current().pid());
                result.add("input", marker.deepCopy());
                var mods = new TreeMap<String, String>();
                ModList.get().getMods().forEach(mod -> mods.put(mod.getModId(), mod.getVersion().toString()));
                require(mods.keySet().containsAll(java.util.Set.of("basepower", "basesteam", "baseelectric", "basemetals", "mmdlib", "orespawn", "advantage_client_integration")), "Missing integrated-client mod: " + mods);
                require(!mods.containsKey("advantageclientresources") && !mods.containsKey("advantageworkstest"), "Worldless probe/harness must not be loaded");
                result.add("loadedMods", new com.google.gson.Gson().toJsonTree(mods));
                if (phase.equals("reload")) require(previous().get("pid").getAsLong() != ProcessHandle.current().pid(), "Reload must use a different full client process");
                state = 1;
                mc.options.pauseOnLostFocus = false;
                mc.createWorldOpenFlows().openWorld("ClientIntegrationWorld", () -> fail(new IllegalStateException("Normal world-open flow aborted")));
                return;
            }
            if (state == 1) {
                if (mc.level == null || mc.player == null || mc.getSingleplayerServer() == null || mc.player.connection == null) return;
                var server = mc.getSingleplayerServer();
                final CompoundTag previousTank = phase.equals("reload") ? TagParser.parseTag(previous().get("tankNbt").getAsString()) : null;
                pending = server.submit(() -> {
                    var level = server.overworld();
                    var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                    require(player != null && player.connection != null, "No real integrated-server player");
                    if (phase.equals("initial")) {
                        require(level.isEmptyBlock(POS), "Integration witness location was not empty");
                        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 3; z++) {
                            BlockPos floor = POS.offset(x, -1, z);
                            require(level.isEmptyBlock(floor), "Integration platform would overwrite a fixture");
                            level.setBlockAndUpdate(floor, net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState());
                        }
                        var block = BuiltInRegistries.BLOCK.get(net.minecraft.resources.ResourceLocation.parse("basepower:fluid_storage_tank"));
                        level.setBlockAndUpdate(POS, block.defaultBlockState());
                        var tank = (StorageTankTileEntity) level.getBlockEntity(POS);
                        tank.getTank().setFluid(new FluidStack(Fluids.WATER, 1375));
                        tank.getPersistentData().putInt("ClientIntegrationTypedInt", 1375);
                        tank.getPersistentData().putString("ClientIntegrationWitness", "real-menu-two-process");
                        tank.setChanged();
                    }
                    var tank = (StorageTankTileEntity) level.getBlockEntity(POS);
                    require(tank != null, "Persisted tank is absent");
                    CompoundTag nbt = tank.saveWithFullMetadata(level.registryAccess());
                    require(nbt.getCompound("Tank").contains("Amount", 3) && nbt.getCompound("Tank").contains("FluidName", 8), "Wrong tank NBT types");
                    require(tank.getTank().getFluidAmount() == 1375 && tank.getTank().getFluid().getFluid() == Fluids.WATER, "Wrong persisted tank fluid/volume");
                    require(tank.getPersistentData().getInt("ClientIntegrationTypedInt") == 1375 && tank.getPersistentData().contains("ClientIntegrationTypedInt", 3), "Persistent typed witness changed");
                    require("real-menu-two-process".equals(tank.getPersistentData().getString("ClientIntegrationWitness")), "Persistent string witness changed");
                    if (previousTank != null) require(nbt.getCompound("Tank").equals(previousTank), "Cold-reload typed tank data changed");
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    player.teleportTo(level, POS.getX() + 0.5, POS.getY(), POS.getZ() + 2.5, java.util.Set.of(), 180, 15);
                    JsonObject observed = new JsonObject();
                    observed.addProperty("tankNbt", nbt.getCompound("Tank").toString());
                    observed.addProperty("serverPlayerUuid", player.getUUID().toString());
                    observed.addProperty("serverGameTime", level.getGameTime());
                    return observed;
                });
                state = 2;
                return;
            }
            if (state == 2) {
                if (!pending.isDone()) return;
                JsonObject observed = pending.join();
                observed.entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
                if (mc.player.distanceToSqr(Vec3.atCenterOf(POS)) > 20 || !mc.level.hasChunkAt(POS)) return;
                if (!(mc.level.getBlockEntity(POS) instanceof StorageTankTileEntity tank) || tank.getTank().getFluidAmount() != 1375) return;
                mc.player.setShiftKeyDown(false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(
                        Vec3.atCenterOf(POS).add(0, 0, 0.5), Direction.SOUTH, POS, false));
                state = 3;
                return;
            }
            if (state == 3) {
                if (!(mc.screen instanceof SimpleMachineGUI.GUIContainer) || !(mc.player.containerMenu instanceof SimpleMachineGUI.Container)) return;
                require(mc.player.containerMenu.getType() == MachineGUIRegistry.TYPE && mc.player.containerMenu.containerId > 0, "Wrong real client menu");
                int clientId = mc.player.containerMenu.containerId;
                var clientTank = (StorageTankTileEntity) mc.level.getBlockEntity(POS);
                require(clientTank.getTank().getFluidAmount() == 1375 && clientTank.getTank().getFluid().getFluid() == Fluids.WATER, "Client tank reading did not synchronize");
                result.addProperty("clientMenuId", clientId);
                result.addProperty("clientMenuClass", mc.player.containerMenu.getClass().getName());
                result.addProperty("clientScreenClass", mc.screen.getClass().getName());
                var server = mc.getSingleplayerServer();
                pending = server.submit(() -> {
                    var player = server.getPlayerList().getPlayer(mc.player.getUUID());
                    require(player.containerMenu.getType() == MachineGUIRegistry.TYPE && player.containerMenu.containerId == clientId, "Server/client menu handshake IDs disagree");
                    require(player.containerMenu.stillValid(player), "Server menu is invalid");
                    JsonObject observed = new JsonObject();
                    observed.addProperty("serverMenuId", player.containerMenu.containerId);
                    observed.addProperty("serverMenuClass", player.containerMenu.getClass().getName());
                    return observed;
                });
                state = 4;
                return;
            }
            if (state == 4) {
                if (!pending.isDone()) return;
                pending.join().entrySet().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
                if (++age < 40) return;
                mc.player.closeContainer();
                mc.level.disconnect();
                mc.disconnect(new GenericMessageScreen(Component.literal("Saving disposable integration world")));
                mc.setScreen(new TitleScreen());
                state = 5;
                return;
            }
            if (state == 5) {
                require(mc.level == null && mc.getSingleplayerServer() == null, "Integrated server has not shut down");
                verifyInputs();
                result.addProperty("status", "PASS");
                result.addProperty("gracefulSaveAndQuit", true);
                Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n", StandardOpenOption.CREATE_NEW);
                state = 99;
                mc.stop();
            }
        } catch (Throwable failure) { fail(failure); }
    }

    private JsonObject previous() throws Exception {
        JsonObject prior = JsonParser.parseString(Files.readString(root.resolve("client-integration-initial.json"))).getAsJsonObject();
        require("PASS".equals(prior.get("status").getAsString()), "Initial process did not pass");
        return prior;
    }

    private void verifyInputs() throws Exception {
        for (var entry : marker.getAsJsonObject("inputSha256").entrySet()) {
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(entry.getKey()))));
            require(actual.equalsIgnoreCase(entry.getValue().getAsString()), "Input changed: " + entry.getKey());
        }
    }

    private void fail(Throwable error) {
        error.printStackTrace();
        try {
            if (root != null) {
                JsonObject failure = result == null ? new JsonObject() : result;
                failure.addProperty("status", "FAIL");
                failure.addProperty("error", error.toString());
                failure.addProperty("state", state);
                Files.writeString(root.resolve("client-integration-failure-" + ProcessHandle.current().pid() + ".json"),
                        new GsonBuilder().setPrettyPrinting().create().toJson(failure), StandardOpenOption.CREATE_NEW);
            }
        } catch (Exception receiptFailure) { receiptFailure.printStackTrace(); }
        state = 99;
        Minecraft mc = Minecraft.getInstance();
        try { if (mc.level != null) mc.level.disconnect(); mc.disconnect(); } finally { mc.stop(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
