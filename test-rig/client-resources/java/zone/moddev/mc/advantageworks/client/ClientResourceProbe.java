package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.resources.Identifier;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import zone.moddev.mc.basepower.init.ItemGroups;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;

/** Runs after real native login and resource baking in a disposable copied world. */
@Mod(ClientResourceProbe.MODID)
public final class ClientResourceProbe {
    static final String MODID = "advantageclientresources";
    private static boolean finished;
    private static int ticks;
    private static String lastReadiness = "";
    private static JsonObject lastReadinessState;
    private static final NativeExperimentalFixtureGate FIXTURE_GATE = new NativeExperimentalFixtureGate();
    private static JsonObject preLoginGeometry;
    private static boolean fixtureWorldOpenRequested;
    private static net.minecraft.client.gui.screens.Screen disconnectedScreen;
    private static int disconnectedTick;
    private static NativeWorldOilProbe worldOil;
    private static JsonObject worldOilReport;
    private static boolean worldOilComplete;
    private static net.minecraft.network.Connection loggedInConnection;

    public ClientResourceProbe() {
        NativeVanillaResourceProbe.register();
        net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn.BUS.addListener(
                event -> loggedInConnection = event.getConnection());
        TickEvent.ClientTickEvent.Post.BUS.addListener(ClientResourceProbe::tick);
    }

    public static void tick(TickEvent.ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("advantageworks.clientResources") || finished) return;
        Minecraft minecraft = Minecraft.getInstance();
        boolean loggedIn = minecraft.level != null && minecraft.player != null
                && minecraft.getConnection() != null && minecraft.hasSingleplayerServer()
                && minecraft.getConnection().getConnection() == loggedInConnection
                && loggedInConnection != null && loggedInConnection.isConnected()
                && minecraft.getConnection().hasClientLoaded();
        boolean componentsBound = loggedIn && net.minecraft.core.registries.BuiltInRegistries.ITEM.listElements()
                .allMatch(net.minecraft.core.Holder.Reference::areComponentsBound);
        ++ticks;
        Throwable readinessFailure = null;
        try {
            recordReadiness(minecraft, loggedIn, componentsBound);
            failDisconnected(minecraft);
            if (Boolean.getBoolean("advantageworks.childRendererGeometry") && preLoginGeometry == null
                    && minecraft.level == null && minecraft.player == null && minecraft.getSingleplayerServer() == null
                    && minecraft.gui.overlay() == null && minecraft.getModelManager().getBlockStateModelSet() != null) {
                preLoginGeometry = new JsonObject();
                inspect("child_geometry", preLoginGeometry, () -> ChildRendererGeometryProbe.verify(minecraft, preLoginGeometry));
                JsonObject context = new JsonObject();
                context.addProperty("stage", "GENUINELY_WORLDLESS_BEFORE_LOGIN");
                context.addProperty("tick", ticks);
                context.addProperty("levelPresent", minecraft.level != null);
                context.addProperty("playerPresent", minecraft.player != null);
                context.addProperty("singleplayerServerPresent", minecraft.getSingleplayerServer() != null);
                preLoginGeometry.add("child_geometry_context", context);
            }
            if (Boolean.getBoolean("advantageworks.openWorldAfterGeometry") && !fixtureWorldOpenRequested
                    && preLoginGeometry != null && minecraft.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen) {
                require(readyForNativeWorldOpen(preLoginGeometry, minecraft.level != null, minecraft.player != null,
                        minecraft.getSingleplayerServer() != null, minecraft.hasSingleplayerServer(),
                        minecraft.getConnection() != null, minecraft.gui.overlay() != null),
                        "Native world open requires completed, genuinely worldless geometry");
                Path runtime = Path.of("").toRealPath();
                Path world = runtime.resolve("saves/AdvantageClientResources").toRealPath();
                require(world.equals(runtime.resolve("saves/AdvantageClientResources"))
                        && Files.isRegularFile(world.resolve("level.dat")), "Missing independent existing client world");
                JsonObject rendezvous = new JsonObject();
                rendezvous.addProperty("protocol", "NATIVE66_WORLDLESS_GEOMETRY_BEFORE_NATIVE_OPEN_V1");
                rendezvous.addProperty("status", "RECORDED_BEFORE_NATIVE_OPEN");
                rendezvous.addProperty("tick", ticks);
                rendezvous.addProperty("world", world.toString());
                rendezvous.addProperty("levelSha256", java.util.HexFormat.of().withUpperCase().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(world.resolve("level.dat")))));
                rendezvous.add("geometry", preLoginGeometry.deepCopy());
                rendezvous.add("readiness", lastReadinessState.deepCopy());
                captureReadinessScreen(minecraft, rendezvous, Path.of("login-readiness/worldless-before-native-open.png"));
                Files.writeString(Path.of("login-readiness/worldless-before-native-open.json"), rendezvous.toString(),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
                fixtureWorldOpenRequested = true;
                minecraft.createWorldOpenFlows().openWorld("AdvantageClientResources", () -> {
                    throw new IllegalStateException("Native fixture world open was cancelled");
                });
            }
            FIXTURE_GATE.tick(minecraft, ticks, lastReadinessState);
        } catch (IOException | java.security.NoSuchAlgorithmException | RuntimeException | LinkageError | AssertionError failure) {
            readinessFailure = failure;
        }
        if (readinessFailure == null && (ticks < 5 || ((!componentsBound || minecraft.gui.overlay() != null
                || minecraft.getModelManager().getBlockStateModelSet() == null) && ticks < 2400))) return;
        if (readinessFailure == null && loggedIn && componentsBound && !worldOilComplete) {
            if (worldOil == null) worldOil = new NativeWorldOilProbe();
            try {
                worldOilReport = worldOil.tick(minecraft, ticks);
                if (worldOilReport == null) return;
            } catch (RuntimeException | LinkageError | AssertionError failure) {
                worldOilReport = worldOil.report();
                LogManager.getLogger(MODID).error("ADVANTAGE_CLIENT_WORLD_OIL FAIL", failure);
            }
            worldOilComplete = true;
        }
        finished = true;
        JsonObject result = new JsonObject();
        try {
            result.addProperty("minecraft", net.minecraftforge.versions.mcp.MCPVersion.getMCVersion());
            result.addProperty("forge", net.minecraftforge.versions.forge.ForgeVersion.getVersion());
            if (lastReadinessState != null) result.add("loginReadiness", lastReadinessState.deepCopy());
            result.add("experimentalFixtureGate", FIXTURE_GATE.report());
            if (preLoginGeometry != null) for (var row : preLoginGeometry.entrySet()) result.add(row.getKey(), row.getValue().deepCopy());
            if (worldOilReport != null) result.add("world_oil_visual", worldOilReport.deepCopy());
            if (readinessFailure != null) throw new IllegalStateException("Native login/fixture gate failed", readinessFailure);
            require("26.3".equals(result.get("minecraft").getAsString())
                    && "66.0.0".equals(result.get("forge").getAsString())
                    && Runtime.version().feature() == 25, "Wrong native client target");
            require(loggedIn && componentsBound, "Native real-world login/component binding did not complete");
            var expectedWorld = Path.of("saves/AdvantageClientResources").toRealPath();
            var actualWorld = minecraft.getSingleplayerServer()
                    .getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toRealPath();
            require(actualWorld.equals(expectedWorld), "Integrated server opened a world other than the disposable copy");
            JsonObject login = new JsonObject();
            login.addProperty("status", "PASS");
            login.addProperty("realWorld", true);
            login.addProperty("integratedServer", true);
            login.addProperty("nativeComponentsBound", true);
            login.addProperty("syntheticComponentBinding", false);
            login.addProperty("loggingInEventObserved", true);
            login.addProperty("connectionConnected", true);
            login.addProperty("clientLoaded", true);
            login.addProperty("copiedWorldPath", actualWorld.toString());
            login.addProperty("itemHolders", net.minecraft.core.registries.BuiltInRegistries.ITEM.listElements().count());
            login.addProperty("dimension", minecraft.level.dimension().identifier().toString());
            result.add("login", login);
            JsonObject calibration = NativeGuiProjection.calibrate(minecraft);
            require("PASS".equals(calibration.get("status").getAsString()), "Native projection calibration failed");
            result.add("projectionCalibration", calibration);
            verifyNetworkBootstrap(result);
            com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
            JsonObject versions = new JsonObject();
            for (String id : new String[]{"basepower", "basesteam", "baseelectric"}) {
                boolean loaded = net.minecraftforge.fml.ModList.isLoaded(id);
                if (loaded) {
                    mods.add(id);
                    versions.addProperty(id, net.minecraftforge.fml.ModList.getModContainerById(id).orElseThrow()
                            .getModInfo().getVersion().toString());
                }
                if (Boolean.getBoolean("advantageworks.requireExtensions")) require(loaded, "Combined launch omitted " + id);
            }
            result.add("advantage_mods", mods);
            result.add("mod_versions", versions);
            require(versions.has("basepower") && zone.moddev.mc.basepower.PowerAdvantage.VERSION
                    .equals(versions.get("basepower").getAsString()), "Power metadata does not match the compiled version");
            require("2.4.18.2603001".equals(versions.get("basepower").getAsString()), "Wrong native Power candidate");
            for (String id : new String[]{"basesteam", "baseelectric"}) {
                if (versions.has(id)) require("2.2.19.2603001".equals(versions.get(id).getAsString()), "Wrong native expansion candidate: " + id);
            }
            require(minecraft.getModelManager().getBlockStateModelSet() != null, "Timed out before model baking");
            require(minecraft.gui.overlay() == null, "Timed out before native resource loading completed");
            verifyVanillaParticleControl(minecraft, result);
            boolean primary = inspect("registered_resources", result, () -> {
            int items = 0;
            int states = 0;
            List<String> resourceFailures = new ArrayList<>();
            var expectedCreative = new java.util.HashSet<Identifier>();
            var hidden = java.util.Set.of("basepower:fluid_pipe_terminal", "basepower:crude_oil", "basepower:refined_oil",
                    "basesteam:pump_pipe_steam", "basesteam:drillbit",
                    "baseelectric:pump_pipe_electric", "baseelectric:laser_turret_evil");
            BlockStateModel missing = minecraft.getModelManager().getBlockStateModelSet().missingModel();
            for (Item item : ForgeRegistries.ITEMS) {
                Identifier id = ForgeRegistries.ITEMS.getKey(item);
                if (id == null || !included(id.getNamespace())) continue;
                ItemStack stack = new ItemStack(item);
                String key = item.getDescriptionId();
                capture("item-name/" + id, resourceFailures,
                        () -> require(!I18n.get(key).equals(key), "Untranslated item: " + id + " -> " + key));
                capture("item-model/" + id, resourceFailures, () -> verifyItem(minecraft, stack, id.toString()));
                if (!hidden.contains(id.toString())) expectedCreative.add(id);
                items++;
            }
            for (Block block : ForgeRegistries.BLOCKS) {
                Identifier id = ForgeRegistries.BLOCKS.getKey(block);
                if (id == null || !included(id.getNamespace())) continue;
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
                    capture("block-model/" + state, resourceFailures,
                            () -> verifyModel(model, missing, state, state.toString(), !(block instanceof net.minecraft.world.level.block.LiquidBlock)));
                    states++;
                }
            }
            CreativeModeTab tab = ItemGroups.tab_powerAdvantage;
            require(tab != null, "The shared creative tab was not registered");
            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(minecraft.level.enabledFeatures(), false,
                    minecraft.level.registryAccess()));
            var contents = tab.getDisplayItems();
            require(items > 0 && states > 0, "Power Advantage content is not registered");
            require(!contents.isEmpty(), "Power Advantage creative tab is empty");
            require(!tab.getIconItem().isEmpty(), "Power Advantage creative tab has no icon");
            require(net.minecraft.locale.Language.getInstance().has("itemGroup.basepower") && tab.getDisplayName().getString().equals(I18n.get("itemGroup.basepower")),
                    "The shared creative tab title is not translated");
            var creativeIds = new java.util.HashSet<Identifier>();
            for (ItemStack stack : contents) {
                Identifier id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                require(id != null && creativeIds.add(id), "Duplicate or unregistered creative entry: " + id);
            }
            var missingCreative = new java.util.HashSet<>(expectedCreative);
            missingCreative.removeAll(creativeIds);
            var extraCreative = new java.util.HashSet<>(creativeIds);
            extraCreative.removeAll(expectedCreative);
            require(missingCreative.isEmpty() && extraCreative.isEmpty(),
                    "Wrong creative tab contents. Missing: " + missingCreative + "; unexpected: " + extraCreative);
            require(I18n.get("block.basepower.still").equals(I18n.get("tile.basepower.still.name")),
                    "Legacy GUI translation differs from modern block name");
            result.addProperty("items", items);
            result.addProperty("block_states", states);
            result.addProperty("creative_entries", contents.size());
            com.google.gson.JsonArray errors = new com.google.gson.JsonArray();
            resourceFailures.forEach(errors::add);
            result.add("resource_errors", errors);
            require(resourceFailures.isEmpty(), "Registered resource failures: " + resourceFailures);
            LogManager.getLogger(MODID).info("ADVANTAGE_CLIENT_RESOURCES PASS items={} block_states={} creative_entries={}", items, states, contents.size());
            });
            primary &= inspect("tank_pixels", result, () -> TankVisualProbe.verify(minecraft, result));
            primary &= inspect("machine_gui", result, () -> MachineGuiLayoutProbe.verify(minecraft, result));
            primary &= inspect("oil_renderer", result, () -> OilRenderProbe.verify(minecraft, result));
            primary &= inspect("recipe_book", result, () -> RecipeBookGroupingProbe.verify(minecraft, result));
            result.addProperty("primary_resource_status", primary ? "PASS" : "FAIL");
            boolean geometry = true;
            if (Boolean.getBoolean("advantageworks.childRendererGeometry"))
                geometry = preLoginGeometry != null && "PASS".equals(preLoginGeometry.getAsJsonObject("scope_results")
                        .getAsJsonObject("child_geometry").get("status").getAsString());
            require(primary && geometry && worldOilComplete && worldOilReport != null
                    && "PASS".equals(worldOilReport.get("status").getAsString()),
                    "One or more native client probe scopes failed; inspect scope_results/world_oil_visual");
            result.addProperty("status", "PASS");
            result.addProperty("screen", minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getName());
        } catch (IOException | RuntimeException | LinkageError | AssertionError failure) {
            result.addProperty("status", "FAIL");
            result.addProperty("error", failure.toString());
            if (failure.getCause() != null) result.addProperty("cause", failure.getCause().toString());
            LogManager.getLogger(MODID).error("ADVANTAGE_CLIENT_RESOURCES FAIL", failure);
        } finally {
            try { Files.write(Paths.get("client-resources.json"), result.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (IOException failure) { throw new IllegalStateException("Could not write client resource result", failure); }
            finally { minecraft.stop(); }
        }
    }

    static boolean readyForNativeWorldOpen(JsonObject geometry, boolean level, boolean player, boolean server,
            boolean integratedServer, boolean connection, boolean overlay) {
        if (level || player || server || integratedServer || connection || overlay || geometry == null
                || !geometry.has("scope_results") || !geometry.has("child_geometry_context")) return false;
        JsonObject scopes = geometry.getAsJsonObject("scope_results");
        if (!scopes.has("child_geometry") || !"PASS".equals(scopes.getAsJsonObject("child_geometry").get("status").getAsString()))
            return false;
        JsonObject context = geometry.getAsJsonObject("child_geometry_context");
        return "GENUINELY_WORLDLESS_BEFORE_LOGIN".equals(context.get("stage").getAsString())
                && !context.get("levelPresent").getAsBoolean() && !context.get("playerPresent").getAsBoolean()
                && !context.get("singleplayerServerPresent").getAsBoolean();
    }

    private static void failDisconnected(Minecraft minecraft) {
        if (minecraft.gui.screen() == null || minecraft.gui.screen().getClass() != net.minecraft.client.gui.screens.DisconnectedScreen.class
                || minecraft.gui.overlay() != null) return;
        if (minecraft.gui.screen() != disconnectedScreen) { disconnectedScreen = minecraft.gui.screen(); disconnectedTick = ticks; return; }
        if (ticks - disconnectedTick < 20) return;
        try {
            lastReadinessState.addProperty("nativeDisconnectNarration", minecraft.gui.screen().getNarrationMessage().getString());
            captureReadinessScreen(minecraft, lastReadinessState, Path.of("login-readiness/disconnected.png"));
            Files.writeString(Path.of("login-readiness/disconnected.json"), lastReadinessState.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) { throw new IllegalStateException("Could not retain native disconnect failure", failure); }
        throw new IllegalStateException("Native client disconnected: " + minecraft.gui.screen().getNarrationMessage().getString());
    }

    private static void captureReadinessScreen(Minecraft minecraft, JsonObject state, Path screenshot) throws IOException {
        var image = NativeGuiProjection.read(minecraft.gameRenderer.mainRenderTarget());
        try (var output = Files.newOutputStream(screenshot, java.nio.file.StandardOpenOption.CREATE_NEW)) {
            require(javax.imageio.ImageIO.write(image, "png", output), "No diagnostic PNG writer");
        }
        JsonObject proof = new JsonObject();
        proof.addProperty("file", screenshot.toString().replace('\\', '/'));
        try {
            proof.addProperty("sha256", java.util.HexFormat.of().withUpperCase().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(screenshot))));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        proof.addProperty("width", image.getWidth()); proof.addProperty("height", image.getHeight());
        proof.addProperty("fenceCompleted", true);
        proof.addProperty("scope", "NATIVE_LOAD_SCREEN_DIAGNOSTIC_NOT_GUI_QUALIFICATION");
        state.add("screenshot", proof);
    }

    private static void recordReadiness(Minecraft minecraft, boolean loggedIn, boolean componentsBound) {
        JsonObject state = new JsonObject();
        state.addProperty("screenClass", minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getName());
        state.addProperty("screenTitle", minecraft.gui.screen() == null ? "" : minecraft.gui.screen().getTitle().getString());
        state.addProperty("screenTitleComponent", minecraft.gui.screen() == null ? "" : minecraft.gui.screen().getTitle().toString());
        state.addProperty("overlayClass", minecraft.gui.overlay() == null ? "none" : minecraft.gui.overlay().getClass().getName());
        state.addProperty("levelPresent", minecraft.level != null);
        state.addProperty("playerPresent", minecraft.player != null);
        state.addProperty("integratedServer", minecraft.hasSingleplayerServer());
        state.addProperty("singleplayerServerPresent", minecraft.getSingleplayerServer() != null);
        state.addProperty("connectionPresent", minecraft.getConnection() != null);
        state.addProperty("loggingInEventObserved", loggedInConnection != null);
        state.addProperty("connectionConnected", minecraft.getConnection() != null && minecraft.getConnection().getConnection().isConnected());
        state.addProperty("clientLoaded", minecraft.getConnection() != null && minecraft.getConnection().hasClientLoaded());
        state.addProperty("nativeComponentsBound", componentsBound);
        state.addProperty("stage", !minecraft.hasSingleplayerServer() ? "BEFORE_INTEGRATED_SERVER"
                : !loggedIn ? "WAITING_NATIVE_LOGIN" : !componentsBound ? "WAITING_COMPONENT_BINDING" : "NATIVE_LOGIN_READY");
        String signature = state.toString();
        lastReadinessState = state;
        if (signature.equals(lastReadiness) && ticks % 300 != 0) return;
        lastReadiness = signature;
        state.addProperty("tick", ticks);
        try {
            Path folder = Path.of("login-readiness");
            Files.createDirectories(folder);
            if (!loggedIn && ticks >= 300 && ticks % 300 == 0) {
                Path screenshot = folder.resolve("screen-" + ticks + ".png");
                captureReadinessScreen(minecraft, state, screenshot);
            }
            Files.writeString(folder.resolve("state-" + ticks + ".json"), state.toString(), java.nio.file.StandardOpenOption.CREATE_NEW);
            LogManager.getLogger(MODID).info("ADVANTAGE_CLIENT_READINESS {}", state);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not retain actual native login readiness", failure);
        }
    }

    private static boolean inspect(String name, JsonObject report, Runnable check) {
        if (!report.has("scope_results")) report.add("scope_results", new JsonObject());
        JsonObject result = new JsonObject();
        report.getAsJsonObject("scope_results").add(name, result);
        try {
            check.run();
            result.addProperty("status", "PASS");
            return true;
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            result.addProperty("status", "FAIL");
            result.addProperty("error", failure.toString());
            LogManager.getLogger(MODID).error("ADVANTAGE_CLIENT_SCOPE FAIL " + name, failure);
            return false;
        }
    }

    private static void capture(String name, List<String> failures, Runnable check) {
        try { check.run(); }
        catch (RuntimeException | LinkageError | AssertionError failure) {
            failures.add(name + ": " + failure);
            LogManager.getLogger(MODID).error("ADVANTAGE_CLIENT_RESOURCE FAIL " + name, failure);
        }
    }

    private static boolean included(String namespace) {
        return "basepower".equals(namespace) || (Boolean.getBoolean("advantageworks.requireExtensions")
                && ("basesteam".equals(namespace) || "baseelectric".equals(namespace)));
    }

    private static void verifyNetworkBootstrap(JsonObject result) {
        try {
            Class.forName("net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket");
            Class<?> protocol = Class.forName("net.minecraft.network.ConnectionProtocol");
            require(protocol.isEnum() && protocol.getEnumConstants().length > 0,
                    "Vanilla network protocols did not initialise");
            result.addProperty("network_bootstrap_status", "PASS");
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("Vanilla packet class is unavailable", failure);
        }
    }

    private static void verifyItem(Minecraft minecraft, ItemStack stack, String id) {
        Identifier modelId = stack.get(net.minecraft.core.component.DataComponents.ITEM_MODEL);
        require(modelId != null && minecraft.getModelManager().getItemModels().containsKey(modelId), "Missing native item definition: " + id);
        ItemStackRenderState state = new ItemStackRenderState();
        minecraft.getItemModelResolver().updateForTopItem(state, stack, net.minecraft.world.item.ItemDisplayContext.GUI,
                minecraft.level, minecraft.player, 0);
        require(!state.isEmpty(), "Empty native item model: " + id);
        var particle = state.pickParticleMaterial(RandomSource.create(42L));
        require(particle != null && !"missingno".equals(particle.sprite().contents().name().getPath()), "Missing particle texture: " + id);
        try {
            var countField = ItemStackRenderState.class.getDeclaredField("activeLayerCount");
            var layersField = ItemStackRenderState.class.getDeclaredField("layers");
            countField.setAccessible(true);
            layersField.setAccessible(true);
            var quadsField = ItemStackRenderState.LayerRenderState.class.getDeclaredField("quads");
            quadsField.setAccessible(true);
            var layers = (ItemStackRenderState.LayerRenderState[]) layersField.get(state);
            int quads = 0;
            for (int i = 0; i < countField.getInt(state); i++) {
                var itemQuads = (net.minecraft.client.resources.model.geometry.ItemQuads) quadsField.get(layers[i]);
                for (BakedQuad quad : itemQuads.all()) {
                    require(!"missingno".equals(quad.materialInfo().sprite().contents().name().getPath()), "Missing face texture: " + id);
                    quads++;
                }
            }
            require(quads > 0, "Empty baked model: " + id);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot inspect native item layers: " + id, failure);
        }
    }

    private static void verifyModel(BlockStateModel model, BlockStateModel missing, BlockState state, String id, boolean requireQuads) {
        require(model != missing, "Missing baked model: " + id);
        require(!"missingno".equals(model.particleMaterial(net.minecraftforge.client.model.data.ModelData.EMPTY)
                .sprite().contents().name().getPath()), "Missing particle texture: " + id);
        RandomSource random = RandomSource.create(42L);
        List<BakedQuad> quads = new ArrayList<>();
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(random, parts, net.minecraftforge.client.model.data.ModelData.EMPTY);
        for (var part : parts) {
            require(!"missingno".equals(part.particleMaterial().sprite().contents().name().getPath()), "Missing part texture: " + id);
            quads.addAll(part.getQuads(null));
            for (Direction side : Direction.values()) quads.addAll(part.getQuads(side));
        }
        require(!requireQuads || !quads.isEmpty(), "Empty baked model: " + id);
        for (BakedQuad quad : quads) require(!"missingno".equals(quad.materialInfo().sprite().contents().name().getPath()), "Missing face texture: " + id);
    }

    private static void verifyVanillaParticleControl(Minecraft minecraft, JsonObject report) {
        var state = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var models = minecraft.getModelManager().getBlockStateModelSet();
        var model = models.get(state);
        var legacy = model.particleMaterial().sprite();
        var nativeIcon = model.particleMaterial(net.minecraftforge.client.model.data.ModelData.EMPTY).sprite();
        var shaperIcon = models.getParticleMaterial(state).sprite();
        JsonObject control = new JsonObject();
        report.add("vanilla_stone_particle_control", control);
        control.addProperty("model_class", model.getClass().getName());
        control.addProperty("deprecated_no_context", legacy.contents().name().toString());
        control.addProperty("native_model_data", nativeIcon.contents().name().toString());
        control.addProperty("native_shaper", shaperIcon.contents().name().toString());
        Identifier expected = Identifier.parse("minecraft:block/stone");
        require(nativeIcon.contents().name().equals(expected) && shaperIcon.contents().name().equals(expected),
                "Native vanilla STONE particle control failed: " + control);
        verifyModel(model, models.missingModel(), state, "vanilla STONE control", true);
        control.addProperty("status", "PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
