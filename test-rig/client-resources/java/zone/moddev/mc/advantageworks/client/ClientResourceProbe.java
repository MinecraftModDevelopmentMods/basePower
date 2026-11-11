package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonObject;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
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
@Mod.EventBusSubscriber(modid = ClientResourceProbe.MODID, value = Dist.CLIENT)
public final class ClientResourceProbe {
    static final String MODID = "advantageclientresources";
    private static boolean finished;
    private static int ticks;

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (!Boolean.getBoolean("advantageworks.clientResources") || finished || event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (++ticks < 5 || ((minecraft.getOverlay() != null || minecraft.getModelManager().getMissingModel() == null) && ticks < 1200)) return;
        finished = true;
        JsonObject result = new JsonObject();
        try {
            result.addProperty("minecraft", net.minecraftforge.versions.mcp.MCPVersion.getMCVersion());
            result.addProperty("forge", net.minecraftforge.versions.forge.ForgeVersion.getVersion());
            require("1.21.1".equals(result.get("minecraft").getAsString())
                    && "52.1.0".equals(result.get("forge").getAsString()), "Wrong native client target");
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
            require("2.4.14.121011".equals(versions.get("basepower").getAsString()), "Wrong native Power candidate");
            for (String id : new String[]{"basesteam", "baseelectric"}) {
                if (versions.has(id)) require("2.2.15.121011".equals(versions.get(id).getAsString()), "Wrong native expansion candidate: " + id);
            }
            require(minecraft.getModelManager().getMissingModel() != null, "Timed out before model baking");
            require(minecraft.getOverlay() == null, "Timed out before native resource loading completed");
            int items = 0;
            int states = 0;
            var expectedCreative = new java.util.HashSet<ResourceLocation>();
            var hidden = java.util.Set.of("basepower:fluid_pipe_terminal", "basepower:crude_oil", "basepower:refined_oil",
                    "basesteam:pump_pipe_steam", "basesteam:drillbit",
                    "baseelectric:pump_pipe_electric", "baseelectric:laser_turret_evil");
            BakedModel missing = minecraft.getModelManager().getMissingModel();
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
                if (id == null || !included(id.getNamespace())) continue;
                ItemStack stack = new ItemStack(item);
                String key = item.getDescriptionId(stack);
                require(!I18n.get(key).equals(key), "Untranslated item: " + id + " -> " + key);
                BakedModel model = minecraft.getItemRenderer().getModel(stack, null, null, 0);
                verifyModel(model, missing, null, id.toString(), true);
                if (!hidden.contains(id.toString())) expectedCreative.add(id);
                items++;
            }
            for (Block block : ForgeRegistries.BLOCKS) {
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
                if (id == null || !included(id.getNamespace())) continue;
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    BakedModel model = minecraft.getModelManager().getBlockModelShaper().getBlockModel(state);
                    verifyModel(model, missing, state, state.toString(), !(block instanceof net.minecraft.world.level.block.LiquidBlock));
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
            var creativeIds = new java.util.HashSet<ResourceLocation>();
            for (ItemStack stack : contents) {
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
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
            TankVisualProbe.verify(minecraft, result);
            MachineGuiLayoutProbe.verify(minecraft, result);
            OilRenderProbe.verify(minecraft, result);
            RecipeBookGroupingProbe.verify(minecraft, result);
            result.addProperty("primary_resource_status", "PASS");
            if (Boolean.getBoolean("advantageworks.childRendererGeometry")) ChildRendererGeometryProbe.verify(minecraft, result);
            result.addProperty("status", "PASS");
            result.addProperty("items", items);
            result.addProperty("block_states", states);
            result.addProperty("creative_entries", contents.size());
            result.addProperty("screen", minecraft.screen == null ? "none" : minecraft.screen.getClass().getName());
            LogManager.getLogger(MODID).info("ADVANTAGE_CLIENT_RESOURCES PASS items={} block_states={} creative_entries={}", items, states, contents.size());
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

    private static void verifyModel(BakedModel model, BakedModel missing, BlockState state, String id, boolean requireQuads) {
        require(model != missing, "Missing baked model: " + id);
        require(!"missingno".equals(model.getParticleIcon(net.minecraftforge.client.model.data.ModelData.EMPTY)
                .contents().name().getPath()), "Missing particle texture: " + id);
        RandomSource random = RandomSource.create(42L);
        List<BakedQuad> quads = new ArrayList<>(model.getQuads(state, null, random));
        for (Direction side : Direction.values()) quads.addAll(model.getQuads(state, side, random));
        require(!requireQuads || !quads.isEmpty(), "Empty baked model: " + id);
        for (BakedQuad quad : quads) require(!"missingno".equals(quad.getSprite().contents().name().getPath()), "Missing face texture: " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
