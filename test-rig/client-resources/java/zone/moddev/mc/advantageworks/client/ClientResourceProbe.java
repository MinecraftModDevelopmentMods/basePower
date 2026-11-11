package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockStateModel;
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
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.RandomSource;

/** Runs after native resource baking, without opening or generating a world. */
@Mod(ClientResourceProbe.MODID)
public final class ClientResourceProbe {
    static final String MODID = "advantageclientresources";
    private static boolean finished;
    private static int ticks;

    public ClientResourceProbe() {
        TickEvent.ClientTickEvent.Post.BUS.addListener(ClientResourceProbe::tick);
    }

    public static void tick(TickEvent.ClientTickEvent.Post event) {
        if (!Boolean.getBoolean("advantageworks.clientResources") || finished) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (++ticks < 5 || ((minecraft.getOverlay() != null || minecraft.getModelManager().getMissingBlockStateModel() == null) && ticks < 1200)) return;
        finished = true;
        JsonObject result = new JsonObject();
        try {
            result.addProperty("minecraft", net.minecraftforge.versions.mcp.MCPVersion.getMCVersion());
            result.addProperty("forge", net.minecraftforge.versions.forge.ForgeVersion.getVersion());
            require("1.21.11".equals(result.get("minecraft").getAsString())
                    && "61.1.0".equals(result.get("forge").getAsString()), "Wrong native client target");
            require(minecraft.level == null && minecraft.player == null && !minecraft.hasSingleplayerServer(),
                    "Resource probe must not open a world or connect to a server");
            verifyNetworkBootstrap(result);
            com.google.gson.JsonArray mods = new com.google.gson.JsonArray();
            JsonObject versions = new JsonObject();
            for (String id : new String[]{"basepower", "basesteam", "baseelectric"}) {
                boolean loaded = net.minecraftforge.fml.ModList.get().isLoaded(id);
                if (loaded) {
                    mods.add(id);
                    versions.addProperty(id, net.minecraftforge.fml.ModList.get().getModContainerById(id).get()
                            .getModInfo().getVersion().toString());
                }
                if (Boolean.getBoolean("advantageworks.requireExtensions")) require(loaded, "Combined launch omitted " + id);
            }
            result.add("advantage_mods", mods);
            result.add("mod_versions", versions);
            require(versions.has("basepower") && zone.moddev.mc.basepower.PowerAdvantage.VERSION
                    .equals(versions.get("basepower").getAsString()), "Power metadata does not match the compiled version");
            require("2.4.15.121111".equals(versions.get("basepower").getAsString()), "Wrong native Power candidate");
            for (String id : new String[]{"basesteam", "baseelectric"}) {
                if (versions.has(id)) require("2.2.16.121111".equals(versions.get(id).getAsString()), "Wrong native expansion candidate: " + id);
            }
            require(minecraft.getModelManager().getMissingBlockStateModel() != null, "Timed out before model baking");
            require(minecraft.getOverlay() == null, "Timed out before native resource loading completed");
            verifyVanillaParticleControl(minecraft, result);
            boolean primary = inspect("registered_resources", result, () -> {
            int items = 0;
            int states = 0;
            List<String> resourceFailures = new ArrayList<>();
            var expectedCreative = new java.util.HashSet<Identifier>();
            var hidden = java.util.Set.of("basepower:fluid_pipe_terminal", "basepower:crude_oil", "basepower:refined_oil",
                    "basesteam:pump_pipe_steam", "basesteam:drillbit",
                    "baseelectric:pump_pipe_electric", "baseelectric:laser_turret_evil");
            BlockStateModel missing = minecraft.getModelManager().getMissingBlockStateModel();
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
                    BlockStateModel model = minecraft.getModelManager().getBlockModelShaper().getBlockModel(state);
                    capture("block-model/" + state, resourceFailures,
                            () -> verifyModel(model, missing, state, state.toString(), !(block instanceof net.minecraft.world.level.block.LiquidBlock)));
                    states++;
                }
            }
            CreativeModeTab tab = ItemGroups.tab_powerAdvantage;
            require(tab != null, "The shared creative tab was not registered");
            tab.buildContents(new CreativeModeTab.ItemDisplayParameters(FeatureFlags.DEFAULT_FLAGS, false,
                    RegistryAccess.EMPTY));
            var contents = tab.getDisplayItems();
            require(items > 0 && states > 0, "Power Advantage content is not registered");
            require(!contents.isEmpty(), "Power Advantage creative tab is empty");
            require(!tab.getIconItem().isEmpty(), "Power Advantage creative tab has no icon");
            require(I18n.exists("itemGroup.basepower") && tab.getDisplayName().getString().equals(I18n.get("itemGroup.basepower")),
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
                geometry = inspect("child_geometry", result, () -> ChildRendererGeometryProbe.verify(minecraft, result));
            require(primary && geometry, "One or more native client probe scopes failed; inspect scope_results");
            result.addProperty("status", "PASS");
            result.addProperty("screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            result.addProperty("status", "FAIL");
            result.addProperty("error", failure.toString());
            LogManager.getLogger(MODID).error("ADVANTAGE_CLIENT_RESOURCES FAIL", failure);
        } finally {
            try { Files.write(Paths.get("client-resources.json"), result.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (IOException failure) { throw new IllegalStateException("Could not write client resource result", failure); }
            finally { minecraft.stop(); }
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
        minecraft.getItemModelResolver().updateForTopItem(state, stack, net.minecraft.world.item.ItemDisplayContext.GUI, null, null, 0);
        require(!state.isEmpty(), "Empty native item model: " + id);
        require(!"missingno".equals(state.pickParticleIcon(RandomSource.create(42L)).contents().name().getPath()), "Missing particle texture: " + id);
        try {
            var countField = ItemStackRenderState.class.getDeclaredField("activeLayerCount");
            var layersField = ItemStackRenderState.class.getDeclaredField("layers");
            countField.setAccessible(true);
            layersField.setAccessible(true);
            var layers = (ItemStackRenderState.LayerRenderState[]) layersField.get(state);
            int quads = 0;
            for (int i = 0; i < countField.getInt(state); i++) {
                for (BakedQuad quad : layers[i].prepareQuadList()) {
                    require(!"missingno".equals(quad.sprite().contents().name().getPath()), "Missing face texture: " + id);
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
        require(!"missingno".equals(model.particleIcon(net.minecraftforge.client.model.data.ModelData.EMPTY)
                .contents().name().getPath()), "Missing particle texture: " + id);
        RandomSource random = RandomSource.create(42L);
        List<BakedQuad> quads = new ArrayList<>();
        for (var part : model.collectParts(random, net.minecraftforge.client.model.data.ModelData.EMPTY, null)) {
            require(!"missingno".equals(part.particleIcon().contents().name().getPath()), "Missing part texture: " + id);
            quads.addAll(part.getQuads(null));
            for (Direction side : Direction.values()) quads.addAll(part.getQuads(side));
        }
        require(!requireQuads || !quads.isEmpty(), "Empty baked model: " + id);
        for (BakedQuad quad : quads) require(!"missingno".equals(quad.sprite().contents().name().getPath()), "Missing face texture: " + id);
    }

    private static void verifyVanillaParticleControl(Minecraft minecraft, JsonObject report) {
        var state = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var shaper = minecraft.getModelManager().getBlockModelShaper();
        var model = shaper.getBlockModel(state);
        var legacy = model.particleIcon();
        var nativeIcon = model.particleIcon(net.minecraftforge.client.model.data.ModelData.EMPTY);
        var shaperIcon = shaper.getParticleIcon(state);
        JsonObject control = new JsonObject();
        report.add("vanilla_stone_particle_control", control);
        control.addProperty("model_class", model.getClass().getName());
        control.addProperty("deprecated_no_context", legacy.contents().name().toString());
        control.addProperty("native_model_data", nativeIcon.contents().name().toString());
        control.addProperty("native_shaper", shaperIcon.contents().name().toString());
        Identifier expected = Identifier.parse("minecraft:block/stone");
        require(nativeIcon.contents().name().equals(expected) && shaperIcon.contents().name().equals(expected),
                "Native vanilla STONE particle control failed: " + control);
        verifyModel(model, minecraft.getModelManager().getMissingBlockStateModel(), state, "vanilla STONE control", true);
        control.addProperty("status", "PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
