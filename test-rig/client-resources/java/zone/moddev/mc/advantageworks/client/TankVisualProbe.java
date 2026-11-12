package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import cyano.poweradvantage.api.GUIBlock;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import cyano.poweradvantage.api.simple.TileEntitySimpleFluidMachine;
import io.netty.buffer.Unpooled;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import zone.moddev.mc.basepower.registry.MachineGUIRegistry;
import zone.moddev.mc.basepower.init.Blocks;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.function.Consumer;

/** Checks native neighbour culling and the actual fluid-bar drawing function. */
final class TankVisualProbe {
    private static final int WIDTH = 176, HEIGHT = 240;
    private static final int PANEL_TOP = 12, GAUGE_X = 80, GAUGE_Y = PANEL_TOP + 9, GAUGE_HEIGHT = 60, TILE = 16;
    private TankVisualProbe() {}

    static void verify(Minecraft minecraft, JsonObject result) {
        boolean metalFloor = floorVisible(Blocks.metal_storage_tank.defaultBlockState());
        boolean portableFloor = floorVisible(Blocks.storage_tank.defaultBlockState());
        result.addProperty("metal_tank_floor_visible", metalFloor);
        result.addProperty("portable_tank_floor_visible", portableFloor);

        require(metalFloor && portableFloor, "Inset tank culled the floor beneath its exposed corners");
        JsonArray cases = new JsonArray();
        result.add("tank_gauge_pixels", cases);
        checkTank(minecraft, Blocks.storage_tank, "portable", "fluid_storage_tank_gui.png", cases);
        checkTank(minecraft, Blocks.metal_storage_tank, "metal", "fluid_metal_tank_gui.png", cases);
        require(cases.asList().stream().allMatch(row -> row.getAsJsonObject().get("different_pixels").getAsInt() == 0),
                "Tank gauge pixels differ from the correctly tinted fluid sprite; see tank-gauge-*.png and tank_gauge_pixels");
        result.addProperty("tank_gauge_pixel_status", "PASS");
    }

    private record Sample(String name, Fluid fluid, String sprite, int tint) {}

    private static void checkTank(Minecraft minecraft, GUIBlock block, String name, String art, JsonArray cases) {
        var state = block.defaultBlockState();
        var factory = (net.minecraft.world.level.block.EntityBlock) block;
        var server = (TileEntitySimpleFluidMachine) factory.newBlockEntity(BlockPos.ZERO, state);
        var client = (TileEntitySimpleFluidMachine) factory.newBlockEntity(BlockPos.ZERO, state);
        IFluidHandler handler = server.getCapability(ForgeCapabilities.FLUID_HANDLER, Direction.UP)
                .orElseThrow(() -> new IllegalStateException(name + " tank has no native Forge fluid capability"));
        int capacity = server.getTank().getCapacity();
        RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        PacketLevel packetLevel = PacketLevel.withRegistries(registries);
        var definition = registeredDefinition(block);
        var inventory = new Inventory(null, new net.minecraft.world.entity.EntityEquipment());
        var container = definition.createMenu(1, inventory, client);
        require(container.getType() == MachineGUIRegistry.TYPE && MachineGUIRegistry.TYPE != null,
                "Tank GUI is not backed by the registered native container type");
        var screen = definition.new GUIContainer(container, inventory, net.minecraft.network.chat.Component.literal(name)) {
            void renderTank(GuiGraphicsExtractor graphics) { extractBackground(graphics, -100, -100, 0F); }
        };
        screen.init(WIDTH, HEIGHT);
        Identifier texture = Identifier.fromNamespaceAndPath("basepower", "textures/gui/container/" + art);
        BufferedImage empty = render(minecraft, screen::renderTank);
        BufferedImage emptyReference = reference(minecraft, texture, null, 0);
        compareEvidence(name, "empty", 0, 0, empty, emptyReference, emptyReference, null, texture, cases);
        for (Sample sample : new Sample[] {
                new Sample("water", net.minecraft.world.level.material.Fluids.WATER, "minecraft:block/water_still",
                        net.minecraft.client.renderer.BiomeColors.getAverageWaterColor(minecraft.level, minecraft.player.blockPosition())),
                new Sample("lava", net.minecraft.world.level.material.Fluids.LAVA, "minecraft:block/lava_still", 0xFFFFFF),
                new Sample("crude-oil", zone.moddev.mc.basepower.init.Fluids.crude_oil, "basepower:blocks/crude_oil_still", 0xFFFFFF),
                new Sample("refined-oil", zone.moddev.mc.basepower.init.Fluids.refined_oil, "basepower:blocks/refined_oil_still", 0xFFFFFF) }) {
            for (int quarters : new int[] { 1, 2, 4 }) {
                int amount = capacity * quarters / 4;
                int rows = GAUGE_HEIGHT * quarters / 4;
                FluidStack fill = new FluidStack(sample.fluid(), amount);
                require(handler.fill(fill, FluidAction.SIMULATE) == amount && server.getTank().getFluidAmount() == 0,
                        name + " tank changed during simulated fill");
                require(handler.fill(fill, FluidAction.EXECUTE) == amount, name + " tank did not fill");
                syncTank(server, client, packetLevel, registries);
                require(client.getTank().getFluidAmount() == amount && client.getTank().getFluid().getFluid() == sample.fluid(),
                        name + " tank update lost fluid identity or amount");
                Sample wrong = new Sample(sample.name(), sample.fluid(), sample.name().equals("water")
                        ? "minecraft:block/lava_still" : "minecraft:block/water_still", sample.tint());
                compareEvidence(name, sample.name(), amount, rows, render(minecraft, screen::renderTank),
                        reference(minecraft, texture, sample, rows), emptyReference,
                        reference(minecraft, texture, wrong, rows), texture, cases);
                require(handler.drain(amount, FluidAction.EXECUTE).getAmount() == amount, name + " tank did not drain");
                syncTank(server, client, packetLevel, registries);
                require(client.getTank().getFluidAmount() == 0, name + " tank update retained old fluid");
            }
        }
    }

    private static SimpleMachineGUI registeredDefinition(GUIBlock block) {
        try {
            var method = MachineGUIRegistry.class.getDeclaredMethod("definition", int.class);
            method.setAccessible(true);
            return (SimpleMachineGUI) method.invoke(null, block.getGuiID());
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Could not resolve the registered tank GUI", failure);
        }
    }

    private static void syncTank(TileEntitySimpleFluidMachine server, TileEntitySimpleFluidMachine client, PacketLevel packetLevel,
            RegistryAccess registries) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        // Forge 50 obtains the packet's registry access from the sending block entity's level.
        server.setLevel(packetLevel);
        try {
            ClientboundBlockEntityDataPacket.STREAM_CODEC.encode(buffer, server.getUpdatePacket());
            ClientboundBlockEntityDataPacket packet = ClientboundBlockEntityDataPacket.STREAM_CODEC.decode(buffer);
            require(!buffer.isReadable(), "Trailing tank update packet bytes");
            require(packet.getPos().equals(client.getBlockPos()) && packet.getType() == client.getType(),
                    "Tank update addressed the wrong client block entity");
            client.onDataPacket(null, packet, registries);
        } finally {
            server.setLevel(null);
            buffer.release();
        }
    }

    /** Provides registries for the native packet factory without opening a world. */
    private static final class PacketLevel extends ClientLevel {
        private RegistryAccess registries;

        private PacketLevel() {
            super(null, null, Level.OVERWORLD, null, 0, 0, null, false, 0, 0);
            throw new AssertionError("Allocate only a registry context, not a connected client world");
        }

        @Override public RegistryAccess registryAccess() { return registries; }

        private static PacketLevel withRegistries(RegistryAccess registries) {
            try {
                // Like the layout probe, bypass ClientLevel's world and connection setup.
                Class<?> allocator = Class.forName("sun.misc.Unsafe");
                Field singleton = allocator.getDeclaredField("theUnsafe");
                singleton.setAccessible(true);
                PacketLevel level = (PacketLevel) allocator.getMethod("allocateInstance", Class.class)
                        .invoke(singleton.get(null), PacketLevel.class);
                level.registries = registries;
                return level;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not create tank packet registry context", failure);
            }
        }
    }

    private static boolean floorVisible(final BlockState tank) {
        BlockGetter world = new BlockGetter() {
            @Override public int getHeight() { return 256; }
            @Override public int getMinY() { return 0; }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) {
                return pos.equals(BlockPos.ZERO.above()) ? tank : net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        };
        return Block.shouldRenderFace(world, BlockPos.ZERO,
                net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), tank, Direction.UP);
    }

    private static BufferedImage reference(Minecraft minecraft, Identifier texture, Sample sample, int rows) {
        return render(minecraft, graphics -> {
            graphics.fillGradient(0, 0, WIDTH, HEIGHT, -1072689136, -804253680);
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    SimpleMachineGUI.playerInventoryBox, 0, 150, 0, 132, 176, 90, 256, 256);
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    texture, 0, PANEL_TOP, 0, 0, WIDTH, 128, 256, 256);
            if (sample != null) {
                var sprite = minecraft.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS)
                        .getSprite(Identifier.parse(sample.sprite()));
                require(sprite.contents().name().equals(Identifier.parse(sample.sprite())), "Missing reference sprite: " + sample.sprite());
                int tint = sample.tint();
                // Use Minecraft's full-sprite blit, not the production UV or clipped-tile calculations.
                graphics.enableScissor(GAUGE_X, GAUGE_Y + GAUGE_HEIGHT - rows, GAUGE_X + TILE, GAUGE_Y + GAUGE_HEIGHT);
                try {
                    for (int y = GAUGE_Y + GAUGE_HEIGHT - rows; y < GAUGE_Y + GAUGE_HEIGHT; y += TILE) {
                        graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                                sprite, GAUGE_X, y, TILE, TILE, tint | 0xFF000000);
                    }
                } finally {
                    graphics.disableScissor();
                }
            }
            graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
                    texture, GAUGE_X - 3, GAUGE_Y - 3, 176, 0, 22, 66, 256, 256);
        });
    }

    private static void compareEvidence(String tank, String fluid, int amount, int rows, BufferedImage actual,
            BufferedImage expected, BufferedImage empty, BufferedImage garbled, Identifier artwork, JsonArray cases) {
        compare(tank, fluid, amount, rows, actual, expected, empty, cases);
        JsonObject result = cases.get(cases.size() - 1).getAsJsonObject();
        result.add("actual_frame", NativeGuiEvidence.frame(actual));
        result.add("expected_frame", NativeGuiEvidence.frame(expected));
        result.add("empty_frame", NativeGuiEvidence.frame(empty));
        result.add("source_artwork", NativeGuiEvidence.source(Minecraft.getInstance(), artwork).proof().deepCopy());
        int different = differences(actual, expected);
        result.addProperty("whole_panel_different_pixels", different);
        if (garbled != null) {
            result.add("garbled_frame", NativeGuiEvidence.frame(garbled));
            result.addProperty("blank_control_different_pixels", differences(empty, expected));
            result.addProperty("garbled_control_different_pixels", differences(garbled, expected));
            require(differences(empty, expected) > 0 && differences(garbled, expected) > 0,
                    "Blank/garbled fluid control was accepted: " + tank + "/" + fluid + "/" + amount);
        }
        require(different == 0, "Whole tank background/frame/player panel differs, not just its gauge: " + tank + "/" + fluid);
    }

    private static int differences(BufferedImage actual, BufferedImage expected) {
        require(actual.getWidth() == expected.getWidth() && actual.getHeight() == expected.getHeight(), "Mismatched framebuffer size");
        int count = 0;
        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++)
            if (actual.getRGB(x, y) != expected.getRGB(x, y)) count++;
        return count;
    }

    private static void compare(String tank, String fluid, int amount, int rows, BufferedImage actual,
            BufferedImage expected, BufferedImage empty, JsonArray cases) {
        int scale = 2;
        int different = 0, visible = 0;
        int bottom = GAUGE_Y + GAUGE_HEIGHT;
        for (int y = GAUGE_Y * scale; y < (bottom + 3) * scale; y++) {
            for (int x = GAUGE_X * scale; x < (GAUGE_X + TILE) * scale; x++) {
                int expectedPixel = y < bottom * scale ? expected.getRGB(x, y) : empty.getRGB(x, y);
                if (actual.getRGB(x, y) != expectedPixel) different++;
                if (expectedPixel != empty.getRGB(x, y)) visible++;
            }
        }
        require(visible >= rows * TILE * scale * scale / 2, "Fluid reference is blank or obscured: " + tank + " " + fluid);
        var result = new JsonObject();
        result.addProperty("tank", tank);
        result.addProperty("fluid", fluid);
        result.addProperty("amount_mb", amount);
        result.addProperty("fill_rows", rows);
        result.addProperty("render_scale", scale);
        result.addProperty("different_pixels", different);
        cases.add(result);
        try {
            String file = "tank-gauge-" + tank + "-" + fluid + "-" + amount;
            ImageIO.write(actual, "png", new File(file + ".png"));
            if (different > 0) ImageIO.write(expected, "png", new File(file + "-expected.png"));
        } catch (IOException failure) {
            throw new IllegalStateException("Could not retain the native tank gauge image", failure);
        }
    }

    private static BufferedImage render(Minecraft minecraft, Consumer<GuiGraphicsExtractor> draw) {
        return NativeGuiProjection.render(minecraft, WIDTH * 2, HEIGHT * 2, 2, 0xFF000000, draw);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
