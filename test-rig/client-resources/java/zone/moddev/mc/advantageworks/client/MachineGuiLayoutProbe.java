package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cyano.poweradvantage.api.GUIBlock;
import cyano.poweradvantage.api.PoweredEntity;
import cyano.poweradvantage.api.fluid.FluidStack;
import cyano.poweradvantage.api.fluid.FluidTank;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.registry.MachineGUIRegistry;

/** Renders every registered machine screen, not just its detached decorations. */
final class MachineGuiLayoutProbe {
    private static final int[][] VIEWPORTS = {{320, 240, 1}, {426, 240, 2}, {640, 360, 1}};

    static void verify(Minecraft minecraft, JsonObject result) {
        if (!Boolean.getBoolean("advantageworks.machineGuiLayouts")) return;
        JsonArray screens = new JsonArray();
        result.add("machine_gui_layouts", screens);
        result.addProperty("machine_gui_text_spec", 2);
        int textChecks = 0, negativeControls = 0;
        List<String> problems = new ArrayList<>();
        LocalPlayer originalPlayer = minecraft.player;
        try {
            JsonObject expectations;
            try (InputStream stream = MachineGuiLayoutProbe.class.getResourceAsStream("/gui-label-expectations.json")) {
                require(stream != null, "Missing independent GUI label expectations");
                expectations = new JsonParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            require("Inventory".equals(net.minecraft.client.resources.language.I18n.get("container.inventory")), "GUI label audit requires en_us");
            // ContainerScreen needs a carried-item inventory, but no world or connection.
            Class<?> allocator = Class.forName("sun.misc.Unsafe");
            Field singleton = allocator.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            LocalPlayer player = (LocalPlayer) allocator.getMethod("allocateInstance", Class.class)
                    .invoke(singleton.get(null), LocalPlayer.class);
            Inventory inventory = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
            Field inventoryField = Player.class.getDeclaredField("inventory");
            inventoryField.setAccessible(true);
            inventoryField.set(player, inventory);
            Field cooldowns = Player.class.getDeclaredField("cooldowns");
            cooldowns.setAccessible(true);
            cooldowns.set(player, new net.minecraft.world.item.ItemCooldowns());
            minecraft.player = player;
            Field definitionsField = MachineGUIRegistry.class.getDeclaredField("DEFINITIONS");
            definitionsField.setAccessible(true);
            Map<?, ?> definitions = (Map<?, ?>) definitionsField.get(null);
            List<Block> blocks = new ArrayList<>();
            ForgeRegistries.BLOCKS.forEach(block -> {
                if (block instanceof GUIBlock && ((GUIBlock) block).getGuiID() > 0) blocks.add(block);
            });
            blocks.sort(Comparator.comparing(block -> ForgeRegistries.BLOCKS.getKey(block).toString()));
            for (Block block : blocks) {
                JsonObject row = new JsonObject();
                String id = ForgeRegistries.BLOCKS.getKey(block).toString();
                row.addProperty("block", id);
                screens.add(row);
                try {
                    Object definition = definitions.get(((GUIBlock) block).getGuiID());
                    require(definition instanceof SimpleMachineGUI, "No shared screen definition");
                    Field textureField = SimpleMachineGUI.class.getDeclaredField("guiDisplayImage");
                    textureField.setAccessible(true);
                    net.minecraft.resources.Identifier texture = (net.minecraft.resources.Identifier) textureField.get(definition);
                    try (var resource = minecraft.getResourceManager().open(texture)) {
                        BufferedImage artwork = ImageIO.read(resource);
                        // The GUI UVs use a logical 256-square atlas; Electric supplies 2x artwork.
                        require(artwork != null && artwork.getWidth() >= 256 && artwork.getWidth() % 256 == 0
                                && artwork.getWidth() == artwork.getHeight(),
                                "Missing or incorrectly sized GUI texture: " + texture);
                        row.addProperty("texture_pixels", artwork.getWidth());
                    }
                    row.addProperty("texture", texture.toString());
                    BlockEntity tile = ((net.minecraft.world.level.block.EntityBlock)block).newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                    require(tile instanceof Container, "GUI block has no inventory tile");
                    GuiWorld world = (GuiWorld) allocator.getMethod("allocateInstance", Class.class)
                            .invoke(singleton.get(null), GuiWorld.class);
                    world.state = block.defaultBlockState();
                    Field clientSide = net.minecraft.world.level.Level.class.getDeclaredField("isClientSide");
                    clientSide.setAccessible(true);
                    clientSide.setBoolean(world, true);
                    Field playerLevel = net.minecraft.world.entity.Entity.class.getDeclaredField("level");
                    playerLevel.setAccessible(true);
                    playerLevel.set(player, world);
                    tile.setLevel(world);
                    SimpleMachineGUI.Container menu = ((SimpleMachineGUI) definition).createMenu(1, inventory, (Container) tile);
                    require(expectations.getAsJsonObject("machines").has(id), "No human-facing label expectation for " + id);
                    String expectedTitle = expectations.getAsJsonObject("machines").get(id).getAsString();
                    Method titleFactory = MachineGUIRegistry.class.getDeclaredMethod("menuTitle", BlockEntity.class, Block.class);
                    titleFactory.setAccessible(true);
                    net.minecraft.network.chat.Component title = (net.minecraft.network.chat.Component) titleFactory.invoke(null, tile, block);
                    RecordingScreen screen = new RecordingScreen((SimpleMachineGUI) definition, menu, inventory, title,
                            expectedTitle, expectations.get("inventory").getAsString());
                    row.addProperty("definition", definition.getClass().getName());
                    row.addProperty("expected_title", expectedTitle);
                    row.addProperty("menu_title", title.getString());
                    row.addProperty("slots", menu.slots.size());
                    int renders = 0;
                    for (int[] viewport : VIEWPORTS) {
                        screen.init(viewport[0], viewport[1]);
                        verifySlots(screen, menu);
                        for (int fill = 0; fill <= 2; fill++) {
                            for (net.minecraft.world.level.block.state.properties.Property<?> property : block.defaultBlockState().getProperties()) {
                                if (property instanceof net.minecraft.world.level.block.state.properties.BooleanProperty && "active".equals(property.getName())) {
                                    world.state = block.defaultBlockState().setValue((net.minecraft.world.level.block.state.properties.BooleanProperty) property, fill > 0);
                                }
                            }
                            populate(tile, fill * 0.5F);
                            BufferedImage actual = render(minecraft, screen, viewport, false);
                            String name = id.replace(':', '-') + "-" + viewport[0] + "x" + viewport[1]
                                    + "-scale" + viewport[2] + "-fill" + fill;
                            File target = new File("machine-gui-layouts/" + name + ".png");
                            target.getParentFile().mkdirs();
                            ImageIO.write(actual, "png", target);
                            require(screen.tooltipCalls > 0, "Native item tooltip rendering was not dispatched");
                            screen.verifyDrawBounds();
                            renders++;
                            BufferedImage background = render(minecraft, screen, viewport, true);
                            screen.labels = LabelMode.EXPECTED;
                            BufferedImage expected = render(minecraft, screen, viewport, false);
                            screen.labels = LabelMode.PRODUCTION;
                            String labelError = labelError(actual, expected, background, screen, viewport[2]);
                            require(labelError == null, labelError);
                            textChecks++;
                            if (fill == 0 && viewport == VIEWPORTS[0]) verifyNoTextOverArtwork(actual, background, screen);
                            require(title.getString().equals(expectedTitle), "Menu title differs from the equipment name: " + title.getString());
                            require(screen.getYSize() == expectations.getAsJsonObject("layout").get("height").getAsInt(), "Missing reserved label space");
                            verifyInventoryRows(menu);
                            require(labelError(background, expected, background, screen, viewport[2]) != null,
                                    "Negative control accepted a screen with no labels");
                            negativeControls++;
                        }
                    }
                    inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2));
                    screen.init(320, 240);
                    BufferedImage tooltip = render(minecraft, screen, VIEWPORTS[0], false, true);
                    require(screen.itemTooltipCalls == 1, "Hovered inventory item has no native tooltip");
                    require(screen.hovered() == menu.slots.get(menu.slots.size() - 9), "Inventory hitbox is misaligned");
                    ImageIO.write(tooltip, "png", new File("machine-gui-layouts/" + id.replace(':', '-') + "-tooltip.png"));
                    inventory.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
                    if (block == zone.moddev.mc.basepower.init.Blocks.metal_storage_tank) {
                        negativeControls += verifyMissingLabelControls(minecraft, screen);
                        verifyLongName(minecraft, (SimpleMachineGUI) definition, menu, inventory);
                    }
                    if (block == zone.moddev.mc.basepower.init.Blocks.metal_storage_tank) {
                        verifyHelperLimits(minecraft, screen);
                    }
                    row.addProperty("renders", renders);
                    row.addProperty("tooltip_and_inventory_hitbox", "PASS");
                    row.addProperty("required_text", "PASS");
                    row.addProperty("blank_label_controls_rejected", 9);
                    row.addProperty("status", "PASS");
                } catch (Exception | LinkageError failure) {
                    inventory.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
                    org.apache.logging.log4j.LogManager.getLogger("advantageclientresources").error("GUI fixture {} failed", id, failure);
                    row.addProperty("status", "FAIL");
                    row.addProperty("error", failure.toString());
                    problems.add(id + ": " + failure);
                }
            }
            result.addProperty("machine_gui_layout_count", screens.size());
            require(!blocks.isEmpty(), "No registered machine screens were audited");
            if (Boolean.getBoolean("advantageworks.requireExtensions")) require(blocks.size() == 39,
                    "Expected all 14 Power, 10 Steam and 15 Electric screens, found " + blocks.size());
            require(problems.isEmpty(), "Machine GUI failures: " + problems);
            result.addProperty("machine_gui_text_status", "PASS");
            result.addProperty("machine_gui_text_checks", textChecks);
            result.addProperty("machine_gui_negative_controls_rejected", negativeControls);
            result.addProperty("machine_gui_layout_status", "PASS");
        } catch (Exception failure) {
            result.addProperty("machine_gui_layout_status", "FAIL");
            throw new IllegalStateException("Machine GUI audit failed", failure);
        } finally {
            minecraft.player = originalPlayer;
        }
    }

    private static void verifySlots(SimpleMachineGUI.GUIContainer screen, SimpleMachineGUI.Container menu) {
        require(screen.getLeft() >= 0 && screen.getTop() >= 0
                && screen.getLeft() + screen.getXSize() <= screen.width
                && screen.getTop() + screen.getYSize() <= screen.height, "Screen is clipped at a supported GUI size");
        require(menu.slots.size() >= 36, "Missing player inventory");
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            require(slot.x >= 0 && slot.y >= 0 && slot.x + 16 <= screen.getXSize()
                    && slot.y + 16 <= screen.getYSize(), "Slot outside the screen: " + i);
            for (int j = 0; j < i; j++) {
                Slot other = menu.slots.get(j);
                require(slot.x + 16 <= other.x || other.x + 16 <= slot.x
                        || slot.y + 16 <= other.y || other.y + 16 <= slot.y, "Overlapping slots: " + i + ", " + j);
            }
        }
    }

    private static void populate(BlockEntity tile, float fraction) throws IllegalAccessException {
        if (tile instanceof PoweredEntity) {
            PoweredEntity powered = (PoweredEntity) tile;
            for (cyano.poweradvantage.api.ConduitType type : powered.getTypes()) {
                powered.setEnergy(powered.getEnergyCapacity(type) * fraction, type);
            }
        }
        for (Class<?> type = tile.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || !FluidTank.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                FluidTank tank = (FluidTank) field.get(tile);
                if (tank != null) tank.setFluid(fraction == 0 ? null
                        : new FluidStack(net.minecraft.world.level.material.Fluids.WATER, (int) (tank.getCapacity() * fraction)));
            }
        }
    }

    private static BufferedImage render(Minecraft minecraft, RecordingScreen screen,
            int[] viewport, boolean backgroundOnly) throws Exception {
        return render(minecraft, screen, viewport, backgroundOnly, false);
    }

    private static BufferedImage render(Minecraft minecraft, RecordingScreen screen,
            int[] viewport, boolean backgroundOnly, boolean hover) throws Exception {
        int width = viewport[0] * viewport[2], height = viewport[1] * viewport[2];
        return NativeGuiProjection.render(minecraft, width, height, viewport[2], 0xFF1E2D3A,
                state -> screen.recordingGraphics(minecraft, state), graphics -> {
            if (backgroundOnly) {
                LabelMode original = screen.labels;
                screen.labels = LabelMode.HIDDEN;
                try { screen.render(graphics, -100, -100, 0F); }
                finally { screen.labels = original; }
            } else {
                Slot hotbar = screen.getMenu().slots.get(screen.getMenu().slots.size() - 9);
                screen.render(graphics, screen.hoverTitle ? screen.width / 2 : hover ? screen.getLeft() + hotbar.x + 1 : -100,
                        screen.hoverTitle ? screen.getTop() + 4 : hover ? screen.getTop() + hotbar.y + 1 : -100, 0F);
            }
        });
    }

    private static String labelError(BufferedImage actual, BufferedImage expected, BufferedImage background,
            SimpleMachineGUI.GUIContainer screen, int scale) {
        int labelIndex = 0;
        for (int[] rect : new int[][]{{-screen.getLeft() + 4, 1, screen.width - screen.getLeft() - 4, 12}, {8, 140, 168, 150}}) {
            int ink = 0, missing = 0;
            for (int y = rect[1]; y < rect[3]; y++) for (int x = rect[0]; x < rect[2]; x++) {
                int px = (screen.getLeft() + x) * scale, py = (screen.getTop() + y) * scale;
                if (expected.getRGB(px, py) != background.getRGB(px, py)) {
                    ink++;
                    if (actual.getRGB(px, py) != expected.getRGB(px, py)) missing++;
                }
            }
            if (ink < 20 || missing != 0) return (labelIndex == 0 ? "Machine name" : "Inventory label")
                    + " missing, incorrect or clipped: expectedInk=" + ink + ", mismatched=" + missing;
            labelIndex++;
        }
        return null;
    }

    private static void verifyInventoryRows(SimpleMachineGUI.Container menu) {
        int first = menu.slots.size() - 36;
        for (int i = 0; i < 36; i++) {
            Slot slot = menu.slots.get(first + i);
            int expectedY = i < 27 ? 158 + (i / 9) * 18 : 216;
            require(slot.y == expectedY && slot.x == 8 + (i % 9) * 18, "Inventory slots do not align with their labelled panel");
        }
    }

    private static void verifyNoTextOverArtwork(BufferedImage actual, BufferedImage hidden, RecordingScreen screen) {
        for (int y = 0; y < screen.getYSize(); y++) {
            if (y < 12 || (y >= 140 && y < 150)) continue;
            for (int x = 0; x < screen.getXSize(); x++) require(actual.getRGB(screen.getLeft() + x, screen.getTop() + y)
                    == hidden.getRGB(screen.getLeft() + x, screen.getTop() + y), "Text is drawn over artwork or inventory slots");
        }
    }

    private static int verifyMissingLabelControls(Minecraft minecraft, RecordingScreen screen) throws Exception {
        screen.labels = LabelMode.EXPECTED;
        BufferedImage expected = render(minecraft, screen, VIEWPORTS[0], false);
        BufferedImage hidden = render(minecraft, screen, VIEWPORTS[0], true);
        for (LabelMode mode : new LabelMode[]{LabelMode.TITLE_ONLY, LabelMode.INVENTORY_ONLY, LabelMode.WRONG_TEXT, LabelMode.OVER_GAUGE}) {
            screen.labels = mode;
            BufferedImage broken = render(minecraft, screen, VIEWPORTS[0], false);
            ImageIO.write(broken, "png", new File("machine-gui-layouts/negative-control-" + mode.name().toLowerCase(java.util.Locale.ROOT) + ".png"));
            require(labelError(broken, expected, hidden, screen, 1) != null, "Label detector accepted negative control " + mode);
        }
        screen.labels = LabelMode.PRODUCTION;
        return 4;
    }

    private static void verifyLongName(Minecraft minecraft, SimpleMachineGUI definition, SimpleMachineGUI.Container menu,
            Inventory inventory) throws Exception {
        String name = String.join(" / ", java.util.Collections.nCopies(16, "John's oil reserve"));
        RecordingScreen screen = new RecordingScreen(definition, menu, inventory,
                net.minecraft.network.chat.Component.literal(name), name, "Inventory");
        screen.init(320, 240);
        BufferedImage actual = render(minecraft, screen, VIEWPORTS[0], false);
        BufferedImage hidden = render(minecraft, screen, VIEWPORTS[0], true);
        screen.labels = LabelMode.EXPECTED;
        BufferedImage expected = render(minecraft, screen, VIEWPORTS[0], false);
        require(labelError(actual, expected, hidden, screen, 1) == null, "Long custom name is clipped instead of being fitted");
        ImageIO.write(actual, "png", new File("machine-gui-layouts/metal-tank-long-name.png"));
        screen.labels = LabelMode.PRODUCTION;
        screen.hoverTitle = true;
        BufferedImage hover = render(minecraft, screen, VIEWPORTS[0], false);
        require(name.equals(screen.titleTooltip), "Full custom name is not available from its caption tooltip");
        ImageIO.write(hover, "png", new File("machine-gui-layouts/metal-tank-long-name-tooltip.png"));
    }

    private static void require(boolean value, String message) {
        if (!value) throw new IllegalStateException(message);
    }

    private static void verifyHelperLimits(Minecraft minecraft, RecordingScreen screen) throws Exception {
        for (String name : new String[]{"zone.moddev.mc.baseelectric.gui.GUIHelper",
                "zone.moddev.mc.basesteam.gui.GUIHelper"}) {
            if (!net.minecraftforge.fml.ModList.get().isLoaded(name.contains("baseelectric") ? "baseelectric" : "basesteam")) continue;
            String[] methods = name.contains("baseelectric") ? new String[]{"drawProgressBar"}
                    : new String[]{"drawDownArrowProgress", "drawFlameProgress"};
            for (String method : methods) {
                Method draw = Class.forName(name).getMethod(method, int.class, int.class, float.class, SimpleMachineGUI.GUIContainer.class);
                for (float value : new float[]{-1F, 0F, 0.5F, 1F, 15F, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
                    screen.helperCheck = () -> {
                        try {
                            screen.draws.clear();
                            draw.invoke(null, screen.getLeft() + 44, screen.getTop() + 46, value, screen);
                            screen.verifyDrawBounds();
                            require((Float.isFinite(value) && value > 0) == !screen.draws.isEmpty(),
                                    "Wrong draw count for " + method + " at " + value);
                        } catch (ReflectiveOperationException failure) {
                            throw new IllegalStateException("Cannot render " + method, failure);
                        }
                    };
                    render(minecraft, screen, VIEWPORTS[0], false);
                    require(screen.helperCheck == null, "Progress helper did not run inside renderBg");
                }
            }
        }
    }

    private static final class GuiWorld extends ClientLevel {
        private net.minecraft.world.level.block.state.BlockState state;
        private GuiWorld() {
            super(null, null, net.minecraft.world.level.Level.OVERWORLD, null, 0, 0, null, false, 0, 0);
            throw new AssertionError("Allocate the read-only GUI fixture without a world constructor");
        }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) { return state; }
        @Override public net.minecraft.world.flag.FeatureFlagSet enabledFeatures() {
            return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
        }
    }

    private static final class RecordingScreen extends SimpleMachineGUI.GUIContainer {
        private record DrawBounds(float left, float top, float right, float bottom, int u, int v, int width, int height) {}

        private final List<DrawBounds> draws = new ArrayList<>();
        private Runnable helperCheck;
        private int tooltipCalls;
        private int itemTooltipCalls;
        private LabelMode labels = LabelMode.PRODUCTION;
        private final String expectedTitle, expectedInventory;
        private boolean hoverTitle;
        private String titleTooltip;
        RecordingScreen(SimpleMachineGUI definition, SimpleMachineGUI.Container menu, Inventory inventory,
                net.minecraft.network.chat.Component title, String expectedTitle, String expectedInventory) {
            definition.super(menu, inventory, title);
            this.expectedTitle = expectedTitle;
            this.expectedInventory = expectedInventory;
        }
        @Override protected void renderLabels(GuiGraphics graphics, int x, int y) {
            if (labels == LabelMode.PRODUCTION) { super.renderLabels(graphics, x, y); return; }
            if (labels == LabelMode.HIDDEN) return;
            if (labels != LabelMode.INVENTORY_ONLY) {
                String text = labels == LabelMode.WRONG_TEXT ? "Untranslated machine.name" : expectedTitle;
                if (font.width(text) > width - 8) {
                    while (font.width(text + "...") > width - 8) text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
                    text += "...";
                }
                int textX = (176 - font.width(text)) / 2;
                graphics.drawString(font, text, textX, labels == LabelMode.OVER_GAUGE ? 20 : 1, 0xFFFFFFFF);
            }
            if (labels != LabelMode.TITLE_ONLY) graphics.drawString(font, expectedInventory, 8, 140, 0xFFFFFFFF);
        }
        @Override public void render(GuiGraphics graphics, int x, int y, float ticks) {
            draws.clear(); tooltipCalls = 0; itemTooltipCalls = 0; titleTooltip = null;
            super.render(graphics, x, y, ticks);
        }
        @Override protected void renderTooltip(GuiGraphics graphics, int x, int y) {
            tooltipCalls++;
            super.renderTooltip(graphics, x, y);
        }
        GuiGraphics recordingGraphics(Minecraft minecraft, net.minecraft.client.gui.render.state.GuiRenderState state) {
            return new GuiGraphics(minecraft, state, -100, -100) {
                @Override public void setTooltipForNextFrame(Font font, List<net.minecraft.network.chat.Component> lines,
                        java.util.Optional<TooltipComponent> component, net.minecraft.world.item.ItemStack item,
                        int x, int y) {
                    itemTooltipCalls++;
                    super.setTooltipForNextFrame(font, lines, component, item, x, y);
                }

                @Override public void setTooltipForNextFrame(Font font, net.minecraft.network.chat.Component text, int x, int y) {
                    titleTooltip = text.getString();
                    super.setTooltipForNextFrame(font, text, x, y);
                }
            };
        }
        Slot hovered() { return hoveredSlot; }
        @Override public void blit(net.minecraft.resources.Identifier texture,
                int x, int y, int u, int v, int width, int height) {
            var pose = getMatrices();
            float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
            for (int px : new int[]{x, x + width}) for (int py : new int[]{y, y + height}) {
                org.joml.Vector2f corner = pose.transformPosition(px, py, new org.joml.Vector2f());
                float relativeX = corner.x - getLeft(), relativeY = corner.y - getTop();
                left = Math.min(left, relativeX);
                top = Math.min(top, relativeY);
                right = Math.max(right, relativeX);
                bottom = Math.max(bottom, relativeY);
            }
            draws.add(new DrawBounds(left, top, right, bottom, u, v, width, height));
            super.blit(texture, x, y, u, v, width, height);
            if (helperCheck != null) {
                Runnable check = helperCheck;
                helperCheck = null;
                check.run();
            }
        }
        void verifyDrawBounds() {
            for (DrawBounds draw : draws) {
                require(draw.width >= 0 && draw.height >= 0 && draw.left >= 0 && draw.top >= 0
                    && draw.right <= getXSize() && draw.bottom <= getYSize()
                    && draw.u >= 0 && draw.v >= 0 && draw.u + draw.width <= 256 && draw.v + draw.height <= 256,
                    "Decoration outside screen or texture: " + draw);
                if (draw.height > 0) require(draw.top >= 12 && (draw.bottom <= 140 || draw.top >= 150),
                        "Artwork intrudes into a required label row: " + draw);
            }
        }
    }

    private enum LabelMode { PRODUCTION, HIDDEN, EXPECTED, TITLE_ONLY, INVENTORY_ONLY, WRONG_TEXT, OVER_GAUGE }
}
