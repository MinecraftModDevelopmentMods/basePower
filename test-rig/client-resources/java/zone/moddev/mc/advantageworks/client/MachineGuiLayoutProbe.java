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
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
        result.addProperty("machine_gui_framebuffer_spec", 3);
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
            require(originalPlayer != null && minecraft.level != null, "Machine GUI requires actual native login");
            // Inventory contents are isolated; the owner retains its real connection and registry context.
            Class<?> allocator = Class.forName("sun.misc.Unsafe");
            Field singleton = allocator.getDeclaredField("theUnsafe");
            singleton.setAccessible(true);
            LocalPlayer player = originalPlayer;
            Inventory inventory = new Inventory(player, new net.minecraft.world.entity.EntityEquipment());
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
                    world.registries = minecraft.level.registryAccess();
                    Field clientSide = net.minecraft.world.level.Level.class.getDeclaredField("isClientSide");
                    clientSide.setAccessible(true);
                    clientSide.setBoolean(world, true);
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
                    JsonArray frameProofs = new JsonArray();
                    row.add("framebuffer_cases", frameProofs);
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
                            JsonObject proof = new JsonObject();
                            proof.add("actual", NativeGuiEvidence.frame(actual));
                            proof.add("expected", NativeGuiEvidence.frame(expected));
                            proof.add("background", NativeGuiEvidence.frame(background));
                            JsonArray viewportProof = new JsonArray();
                            for (int value : viewport) viewportProof.add(value);
                            proof.add("viewport", viewportProof);
                            proof.addProperty("fill", fill);
                            proof.addProperty("left", screen.getLeft()); proof.addProperty("top", screen.getTop());
                            proof.add("slot_pixels", verifySlotPixels(minecraft, actual, screen, viewport, texture));
                            frameProofs.add(proof);
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
                    row.add("garbled_background_control", verifyGarbledBackgroundControl(minecraft, screen, texture));
                    inventory.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 2));
                    screen.init(320, 240);
                    if (!result.has("native_tooltip_dispatch_control")) {
                        screen.legacyTooltipHookControl = true;
                        try {
                            BufferedImage legacyTooltip = render(minecraft, screen, VIEWPORTS[0], false, true);
                            require(screen.tooltipCalls > 0 && screen.itemTooltipCalls == 0
                                    && screen.nativeSevenArgumentCalls == 1 && screen.legacySixArgumentCalls == 0,
                                    "Old six-argument hook unexpectedly observed the native tooltip call");
                            require(screen.hovered() == menu.slots.get(menu.slots.size() - 9),
                                    "Legacy-hook negative control did not hover the actual inventory item");
                            JsonObject frame = NativeGuiEvidence.frame(legacyTooltip);
                            JsonObject extraction = JsonParser.parseString(java.nio.file.Files.readString(
                                    java.nio.file.Path.of(frame.getAsJsonObject("native").get("file").getAsString()))).getAsJsonObject();
                            String expectedTooltip = inventory.getItem(0).getHoverName().getString();
                            boolean nativeText = false;
                            for (var text : extraction.getAsJsonArray("texts"))
                                nativeText |= expectedTooltip.equals(text.getAsJsonObject().get("text").getAsString());
                            require(nativeText, "Legacy-hook negative control lacks the real native item tooltip text");
                            JsonObject control = new JsonObject();
                            control.addProperty("status", "PASS");
                            control.addProperty("old_six_argument_calls", screen.legacySixArgumentCalls);
                            control.addProperty("native_seven_argument_calls", screen.nativeSevenArgumentCalls);
                            control.addProperty("legacy_mode_seven_argument_counter", screen.itemTooltipCalls);
                            control.addProperty("native_tooltip_text", expectedTooltip);
                            control.addProperty("native_tooltip_rendered", true);
                            control.add("frame", frame);
                            result.add("native_tooltip_dispatch_control", control);
                        } finally {
                            screen.legacyTooltipHookControl = false;
                        }
                    }
                    BufferedImage tooltip = render(minecraft, screen, VIEWPORTS[0], false, true);
                    require(screen.itemTooltipCalls == 1, "Hovered inventory item has no native tooltip");
                    require(screen.itemTooltipExtraSpaceAfterFirstLine,
                            "Native inventory tooltip did not preserve extraSpaceAfterFirstLine=true");
                    require(screen.hovered() == menu.slots.get(menu.slots.size() - 9), "Inventory hitbox is misaligned");
                    ImageIO.write(tooltip, "png", new File("machine-gui-layouts/" + id.replace(':', '-') + "-tooltip.png"));
                    inventory.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
                    if (block == zone.moddev.mc.basepower.init.Blocks.metal_storage_tank) {
                        negativeControls += verifyMissingLabelControls(minecraft, screen);
                        verifyLongName(minecraft, (SimpleMachineGUI) definition, menu, inventory);
                    }
                    if (block == zone.moddev.mc.basepower.init.Blocks.metal_storage_tank) {
                        verifyHelperLimits(minecraft, screen);
                        negativeControls += verifyBackgroundControl(minecraft, screen);
                    }
                    row.addProperty("renders", renders);
                    row.addProperty("tooltip_and_inventory_hitbox", "PASS");
                    row.addProperty("background_extraction_calls", 1);
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

    private static void verifySlots(RecordingScreen screen, SimpleMachineGUI.Container menu) {
        require(screen.getLeft() >= 0 && screen.getTop() >= 0
                && screen.getLeft() + screen.getXSize() <= screen.width
                && screen.getTop() + screen.getYSize() <= screen.height, "Screen is clipped at a supported GUI size");
        require(menu.slots.size() >= 36, "Missing player inventory");
        require(menu.slots.size() == screen.expectedMachineSlots.size() + 36, "Machine slot count differs from GUI declaration");
        for (int i = 0; i < screen.expectedMachineSlots.size(); i++) {
            int[] expected = screen.expectedMachineSlots.get(i);
            require(menu.slots.get(i).x == expected[0] && menu.slots.get(i).y == expected[1],
                    "Machine slot differs from independent GUI declaration: " + i);
        }
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
        BufferedImage image = NativeGuiProjection.render(minecraft, width, height, viewport[2], 0xFF1E2D3A,
                state -> screen.recordingGraphics(minecraft, state), graphics -> {
            screen.beginFrame();
            if (screen.duplicateBackground) screen.extractBackground(graphics, -100, -100, 0F);
            if (backgroundOnly) {
                LabelMode original = screen.labels;
                screen.labels = LabelMode.HIDDEN;
                try { screen.extractRenderStateWithTooltipAndSubtitles(graphics, -100, -100, 0F); }
                finally { screen.labels = original; }
            } else {
                Slot hotbar = screen.getMenu().slots.get(screen.getMenu().slots.size() - 9);
                screen.extractRenderStateWithTooltipAndSubtitles(graphics, screen.hoverTitle ? screen.width / 2 : hover ? screen.getLeft() + hotbar.x + 1 : -100,
                        screen.hoverTitle ? screen.getTop() + 4 : hover ? screen.getTop() + hotbar.y + 1 : -100, 0F);
            }
        });
        if (screen.duplicateBackground) ImageIO.write(image, "png", new File("machine-gui-layouts/double-background-control.png"));
        require(screen.backgroundCalls == 1, "Native screen background must be extracted exactly once, got " + screen.backgroundCalls);
        return image;
    }

    private static int verifyBackgroundControl(Minecraft minecraft, RecordingScreen screen) throws Exception {
        screen.duplicateBackground = true;
        try {
            boolean rejected = false;
            try { render(minecraft, screen, VIEWPORTS[0], false); }
            catch (IllegalStateException expected) {
                if (!expected.getMessage().startsWith("Native screen background must be extracted exactly once")) throw expected;
                rejected = true;
            }
            require(rejected, "Duplicate native background extraction was not rejected");
            return 1;
        } finally {
            screen.duplicateBackground = false;
        }
    }

    private static JsonObject verifyGarbledBackgroundControl(Minecraft minecraft, RecordingScreen screen,
            net.minecraft.resources.Identifier texture) throws Exception {
        screen.init(VIEWPORTS[0][0], VIEWPORTS[0][1]);
        screen.garbleBackground = true;
        try {
            BufferedImage broken = render(minecraft, screen, VIEWPORTS[0], false);
            boolean rejected = false;
            try { verifySlotPixels(minecraft, broken, screen, VIEWPORTS[0], texture); }
            catch (IllegalStateException expected) {
                if (!expected.getMessage().startsWith("Slot framebuffer pixels differ")) throw expected;
                rejected = true;
            }
            require(rejected, "Garbled native background was accepted by slot pixel checks");
            return NativeGuiEvidence.frame(broken);
        } finally {
            screen.garbleBackground = false;
        }
    }

    private static JsonObject verifySlotPixels(Minecraft minecraft, BufferedImage actual, RecordingScreen screen,
            int[] viewport, net.minecraft.resources.Identifier texture) {
        var inventory = NativeGuiEvidence.source(minecraft, SimpleMachineGUI.playerInventoryBox);
        var machine = NativeGuiEvidence.source(minecraft, texture);
        JsonObject proof = new JsonObject();
        proof.add("inventory_source", inventory.proof().deepCopy());
        proof.add("machine_source", machine.proof().deepCopy());
        JsonArray points = new JsonArray(), slots = new JsonArray(), slotColors = new JsonArray(), expectedPositions = new JsonArray();
        for (int[] pos : screen.expectedMachineSlots) {
            JsonArray row = new JsonArray(); row.add(pos[0] - 1); row.add(pos[1] - 1); expectedPositions.add(row);
        }
        for (int y = 0; y < 18; y++) for (int x = 0; x < 18; x++) if (x == 0 || x == 17 || y == 0 || y == 17) {
            JsonArray point = new JsonArray(); point.add(x); point.add(y);
            point.add(texel(inventory.image(), 7 + x, 139 + y)); points.add(point);
        }
        int firstPlayer = screen.getMenu().slots.size() - 36;
        int checked = 0;
        for (int index = 0; index < screen.getMenu().slots.size(); index++) {
            Slot slot = screen.getMenu().slots.get(index);
            JsonArray position = new JsonArray(); position.add(slot.x - 1); position.add(slot.y - 1); slots.add(position);
            boolean player = index >= firstPlayer;
            BufferedImage source = player ? inventory.image() : machine.image();
            int u = slot.x - 1, v = player ? 132 + slot.y - 150 - 1 : slot.y - 12 - 1;
            JsonArray colors = new JsonArray();
            java.util.Set<Integer> contrast = new java.util.HashSet<>();
            for (int y = 0; y < 18; y++) for (int x = 0; x < 18; x++) {
                if (x != 0 && x != 17 && y != 0 && y != 17) continue;
                int reference = texel(source, u + x, v + y);
                if (player) require(reference == texel(inventory.image(), 7 + x, 139 + y), "Player inventory border changed");
                for (int dy = 0; dy < viewport[2]; dy++) for (int dx = 0; dx < viewport[2]; dx++) {
                    int physicalReference = texel(source, u + x, v + y, dx, dy, viewport[2]);
                    colors.add(physicalReference); contrast.add(physicalReference);
                    int pixelX = (screen.getLeft() + slot.x - 1 + x) * viewport[2] + dx;
                    int pixelY = (screen.getTop() + slot.y - 1 + y) * viewport[2] + dy;
                    require(actual.getRGB(pixelX, pixelY) == physicalReference,
                            "Slot framebuffer pixels differ from independent artwork: " + index + " at " + pixelX + "," + pixelY);
                    checked++;
                }
            }
            require(contrast.size() >= 2, "Slot source crop has no border contrast: " + index + "/" + texture);
            slotColors.add(colors);
        }
        proof.add("border_points", points); proof.add("slots", slots);
        proof.add("slot_border_colors", slotColors); proof.add("expected_machine_slots", expectedPositions);
        proof.addProperty("logical_texture_size", 256);
        proof.addProperty("source_sampling", "PHYSICAL_PIXEL_CENTERS_ON_256_LOGICAL_UV_V1");
        proof.addProperty("checked_pixels", checked);
        return proof;
    }

    private static int texel(BufferedImage source, int u, int v) {
        return texel(source, u, v, 0, 0, 1);
    }

    static int texel(BufferedImage source, int u, int v, int dx, int dy, int scale) {
        require(u >= 0 && v >= 0 && u < 256 && v < 256, "Slot source UV escaped the logical GUI atlas");
        require(scale >= 1 && dx >= 0 && dx < scale && dy >= 0 && dy < scale, "Invalid physical source texel sample");
        return source.getRGB((int) ((u + (dx + 0.5F) / scale) * source.getWidth() / 256F),
                (int) ((v + (dy + 0.5F) / scale) * source.getHeight() / 256F));
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
            if (!net.minecraftforge.fml.ModList.isLoaded(name.contains("baseelectric") ? "baseelectric" : "basesteam")) continue;
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
        private net.minecraft.core.RegistryAccess registries;
        private GuiWorld() {
            super(null, null, net.minecraft.world.level.Level.OVERWORLD, null, 0, 0, null, false, 0, 0);
            throw new AssertionError("Allocate the read-only GUI fixture without a world constructor");
        }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) { return state; }
        @Override public net.minecraft.core.RegistryAccess registryAccess() { return registries; }
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
        private boolean itemTooltipExtraSpaceAfterFirstLine;
        private int legacySixArgumentCalls;
        private int nativeSevenArgumentCalls;
        private boolean legacyTooltipHookControl;
        private LabelMode labels = LabelMode.PRODUCTION;
        private final String expectedTitle, expectedInventory;
        private final List<int[]> expectedMachineSlots = new ArrayList<>();
        private boolean hoverTitle;
        private String titleTooltip;
        RecordingScreen(SimpleMachineGUI definition, SimpleMachineGUI.Container menu, Inventory inventory,
                net.minecraft.network.chat.Component title, String expectedTitle, String expectedInventory) {
            definition.super(menu, inventory, title);
            this.expectedTitle = expectedTitle;
            this.expectedInventory = expectedInventory;
            try {
                Field coordinates = SimpleMachineGUI.class.getDeclaredField("inventorySlotCoordinates");
                coordinates.setAccessible(true);
                for (var point : (zone.moddev.mc.basepower.math.Integer2D[]) coordinates.get(definition))
                    expectedMachineSlots.add(new int[]{point.X, point.Y + 12});
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Cannot read the independent GUI slot declaration", failure);
            }
        }
        private int backgroundCalls;
        private boolean duplicateBackground;
        private boolean garbleBackground;

        @Override protected void extractLabels(GuiGraphicsExtractor graphics, int x, int y) {
            if (labels == LabelMode.PRODUCTION) { super.extractLabels(graphics, x, y); return; }
            if (labels == LabelMode.HIDDEN) return;
            if (labels != LabelMode.INVENTORY_ONLY) {
                String text = labels == LabelMode.WRONG_TEXT ? "Untranslated machine.name" : expectedTitle;
                if (font.width(text) > width - 8) {
                    while (font.width(text + "...") > width - 8) text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
                    text += "...";
                }
                int textX = (176 - font.width(text)) / 2;
                graphics.text(font, text, textX, labels == LabelMode.OVER_GAUGE ? 20 : 1, 0xFFFFFFFF);
            }
            if (labels != LabelMode.TITLE_ONLY) graphics.text(font, expectedInventory, 8, 140, 0xFFFFFFFF);
        }
        void beginFrame() {
            draws.clear(); tooltipCalls = 0; itemTooltipCalls = 0; titleTooltip = null;
            itemTooltipExtraSpaceAfterFirstLine = false;
            legacySixArgumentCalls = 0;
            nativeSevenArgumentCalls = 0;
            backgroundCalls = 0;
        }
        @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float ticks) {
            backgroundCalls++;
            super.extractBackground(graphics, x, y, ticks);
        }
        @Override protected void extractTooltip(GuiGraphicsExtractor graphics, int x, int y) {
            tooltipCalls++;
            super.extractTooltip(graphics, x, y);
        }
        GuiGraphicsExtractor recordingGraphics(Minecraft minecraft, net.minecraft.client.renderer.state.gui.GuiRenderState state) {
            return new GuiGraphicsExtractor(minecraft, state, -100, -100) {
                // This old instrumentation remains a counterfactual: the native screen must bypass it.
                @Override public void setTooltipForNextFrame(Font font, List<net.minecraft.network.chat.Component> lines,
                        java.util.Optional<TooltipComponent> component, net.minecraft.world.item.ItemStack item,
                        int x, int y) {
                    if (legacyTooltipHookControl) legacySixArgumentCalls++;
                    super.setTooltipForNextFrame(font, lines, component, item, x, y);
                }

                @Override public void setTooltipForNextFrame(Font font, List<net.minecraft.network.chat.Component> lines,
                        java.util.Optional<TooltipComponent> component, net.minecraft.world.item.ItemStack item,
                        int x, int y, boolean extraSpaceAfterFirstLine) {
                    nativeSevenArgumentCalls++;
                    if (!legacyTooltipHookControl) itemTooltipCalls++;
                    itemTooltipExtraSpaceAfterFirstLine = extraSpaceAfterFirstLine;
                    super.setTooltipForNextFrame(font, lines, component, item, x, y, extraSpaceAfterFirstLine);
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
            super.blit(texture, x, y, garbleBackground ? u + 1 : u, v, width, height);
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
