package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.model.BakedQuad;
import net.minecraft.client.renderer.model.IBakedModel;
import net.minecraft.client.resources.I18n;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.NonNullList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import zone.moddev.mc.basepower.init.ItemGroups;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

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
        if (++ticks < 5 || (minecraft.getModelManager().getMissingModel() == null && ticks < 1200)) return;
        finished = true;
        JsonObject result = new JsonObject();
        try {
            result.addProperty("minecraft", net.minecraftforge.versions.mcp.MCPVersion.getMCVersion());
            result.addProperty("forge", net.minecraftforge.versions.forge.ForgeVersion.getVersion());
            require("1.13.2".equals(result.get("minecraft").getAsString())
                    && "25.0.223".equals(result.get("forge").getAsString()), "Wrong native client target");
            require(minecraft.world == null && minecraft.player == null && !minecraft.isIntegratedServerRunning(),
                    "Resource probe must not open a world or connect to a server");
            JsonArray mods = new JsonArray();
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
            require("2.4.5.113021".equals(versions.get("basepower").getAsString()), "Wrong native Power candidate");
            for (String id : new String[]{"basesteam", "baseelectric"}) {
                if (versions.has(id)) require("2.2.7.113021".equals(versions.get(id).getAsString()), "Wrong native expansion candidate: " + id);
            }
            require(minecraft.getModelManager().getMissingModel() != null, "Timed out before model baking");
            int items = 0;
            int states = 0;
            IBakedModel missing = minecraft.getModelManager().getMissingModel();
            for (Item item : ForgeRegistries.ITEMS) {
                ResourceLocation id = item.getRegistryName();
                if (id == null || !included(id.getNamespace())) continue;
                ItemStack stack = new ItemStack(item);
                String key = item.getTranslationKey(stack);
                require(!I18n.format(key).equals(key), "Untranslated item: " + id + " -> " + key);
                IBakedModel model = minecraft.getItemRenderer().getItemModelWithOverrides(stack, null, null);
                verifyModel(model, missing, null, id.toString(), true);
                items++;
            }
            for (Block block : ForgeRegistries.BLOCKS) {
                ResourceLocation id = block.getRegistryName();
                if (id == null || !included(id.getNamespace())) continue;
                for (IBlockState state : block.getStateContainer().getValidStates()) {
                    IBakedModel model = minecraft.getModelManager().getBlockModelShapes().getModel(state);
                    verifyModel(model, missing, state, state.toString(), !(block instanceof net.minecraft.block.BlockFlowingFluid));
                    states++;
                }
            }
            require(items > 0 && states > 0, "Power Advantage content is not registered");
            int creativeEntries = verifyCreativeTab(result);
            require(I18n.format("block.basepower.still").equals(I18n.format("tile.basepower.still.name")),
                    "Legacy GUI translation differs from modern block name");
            TankVisualProbe.verify(minecraft, result);
            MachineGuiLayoutProbe.verify(minecraft, result);
            OilRenderProbe.verify(minecraft, result);
            RecipeBookGroupingProbe.verify(minecraft, result);
            result.addProperty("status", "PASS");
            result.addProperty("items", items);
            result.addProperty("block_states", states);
            result.addProperty("creative_entries", creativeEntries);
            result.addProperty("screen", minecraft.currentScreen == null ? "none" : minecraft.currentScreen.getClass().getName());
            LogManager.getLogger(MODID).info("ADVANTAGE_CLIENT_RESOURCES PASS items={} block_states={} creative_entries={}", items, states, creativeEntries);
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            result.addProperty("status", "FAIL");
            result.addProperty("error", failure.toString());
            LogManager.getLogger(MODID).error("ADVANTAGE_CLIENT_RESOURCES FAIL", failure);
        } finally {
            try { Files.write(Paths.get("client-resources.json"), result.toString().getBytes(StandardCharsets.UTF_8)); }
            catch (IOException failure) { throw new IllegalStateException("Could not write client resource result", failure); }
            finally { minecraft.shutdown(); }
        }
    }

    private static int verifyCreativeTab(JsonObject result) {
        ItemGroup shared = ItemGroups.tab_powerAdvantage;
        // Fluids use buckets; the other entries are generated machine parts or variants.
        Set<String> hidden = new HashSet<>(Arrays.asList(
                "basepower:crude_oil", "basepower:refined_oil", "basepower:fluid_pipe_terminal",
                "basesteam:drillbit", "basesteam:pump_pipe_steam",
                "baseelectric:laser_turret_evil", "baseelectric:pump_pipe_electric"));
        Set<String> expected = new HashSet<>();
        for (Item item : ForgeRegistries.ITEMS) {
            ResourceLocation id = item.getRegistryName();
            if (id != null && included(id.getNamespace()) && !hidden.contains(id.toString())) {
                expected.add(id.toString());
            }
        }

        int matchingTabs = 0;
        JsonArray tabs = new JsonArray();
        JsonArray visible = new JsonArray();
        List<String> failures = new ArrayList<>();
        for (ItemGroup tab : ItemGroup.GROUPS) {
            if (tab == null) continue;
            String label = tab.getTabLabel();
            tabs.add(label);
            if ("basepower".equals(label) || "poweradvantage".equals(label)
                    || "basesteam".equals(label) || "steamadvantage".equals(label)
                    || "baseelectric".equals(label) || "electricadvantage".equals(label)) matchingTabs++;
            if (tab == ItemGroup.SEARCH || tab == ItemGroup.HOTBAR || tab == ItemGroup.INVENTORY) continue;
            NonNullList<ItemStack> contents = NonNullList.create();
            tab.fill(contents);
            for (ItemStack stack : contents) {
                ResourceLocation id = stack.getItem().getRegistryName();
                if (id == null || !included(id.getNamespace())) continue;
                String name = id.toString();
                if (tab != shared) failures.add(name + " is on " + label);
                else if (!expected.remove(name)) failures.add("Unexpected or duplicate stack: " + name);
                else visible.add(name);
            }
        }
        result.add("creative_tab_labels", tabs);
        result.add("creative_visible_items", visible);
        result.addProperty("creative_tab_title", I18n.format(shared.getTranslationKey()));
        if (matchingTabs != 1) failures.add("Expected one base* creative tab, found " + matchingTabs);
        if (!"basePower".equals(I18n.format(shared.getTranslationKey()))) failures.add("Wrong creative tab title");
        if (!new ResourceLocation("basepower", "sprocket").equals(shared.getIcon().getItem().getRegistryName())) {
            failures.add("Wrong creative tab icon");
        }
        if (!expected.isEmpty()) failures.add("Missing creative items: " + expected);
        require(failures.isEmpty(), failures.toString());
        return visible.size();
    }

    private static boolean included(String namespace) {
        return "basepower".equals(namespace) || (Boolean.getBoolean("advantageworks.requireExtensions")
                && ("basesteam".equals(namespace) || "baseelectric".equals(namespace)));
    }

    private static void verifyModel(IBakedModel model, IBakedModel missing, IBlockState state, String id, boolean requireQuads) {
        require(model != missing, "Missing baked model: " + id);
        require(!"missingno".equals(model.getParticleTexture().getName().getPath()), "Missing particle texture: " + id);
        Random random = new Random(42L);
        List<BakedQuad> quads = new ArrayList<>(model.getQuads(state, null, random));
        for (EnumFacing side : EnumFacing.values()) quads.addAll(model.getQuads(state, side, random));
        require(!requireQuads || !quads.isEmpty(), "Empty baked model: " + id);
        for (BakedQuad quad : quads) require(!"missingno".equals(quad.getSprite().getName().getPath()), "Missing face texture: " + id);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
