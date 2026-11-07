package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonObject;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import zone.moddev.mc.basepower.gui.FilteredFluidTankGUI;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler.FluidAction;
import zone.moddev.mc.basepower.registry.MachineGUIRegistry;
import zone.moddev.mc.basepower.init.Blocks;
import zone.moddev.mc.basepower.machines.fluidmachines.MetalTankTileEntity;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Checks native neighbour culling and the actual fluid-bar drawing function. */
final class TankVisualProbe {
    private static final int GL_PROJECTION = 5889, GL_MODELVIEW = 5888;
    private static final int GL_COLOR_BUFFER_BIT = 16384, GL_RGBA = 6408, GL_UNSIGNED_BYTE = 5121;
    private TankVisualProbe() {}

    static void verify(Minecraft minecraft, JsonObject result) {
        boolean metalFloor = floorVisible(Blocks.metal_storage_tank.defaultBlockState());
        boolean portableFloor = floorVisible(Blocks.storage_tank.defaultBlockState());
        result.addProperty("metal_tank_floor_visible", metalFloor);
        result.addProperty("portable_tank_floor_visible", portableFloor);

        MetalTankTileEntity serverTank = new MetalTankTileEntity(BlockPos.ZERO,
                zone.moddev.mc.basepower.init.Blocks.metal_storage_tank.defaultBlockState());
        IFluidHandler handler = serverTank.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, Direction.UP)
                .orElseThrow(() -> new IllegalStateException("Metal tank has no native Forge fluid capability"));
        require(handler.fill(new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 10000), FluidAction.SIMULATE) == 10000
                && serverTank.getTank().getFluidAmount() == 0, "Native simulated fill changed the tank");
        require(handler.fill(new FluidStack(net.minecraft.world.level.material.Fluids.WATER, 10000), FluidAction.EXECUTE) == 10000,
                "Native capability did not fill the tank");
        MetalTankTileEntity clientTank = new MetalTankTileEntity(BlockPos.ZERO,
                zone.moddev.mc.basepower.init.Blocks.metal_storage_tank.defaultBlockState());
        clientTank.onDataPacket(null, serverTank.getUpdatePacket());
        result.addProperty("synced_tank_amount", clientTank.getTank().getFluidAmount());
        require(clientTank.getTank().getFluidAmount() == 10000, "Filled tank packet lost water");
        require(handler.drain(10000, FluidAction.EXECUTE).getAmount() == 10000, "Native capability did not drain the tank");
        clientTank.onDataPacket(null, serverTank.getUpdatePacket());
        require(clientTank.getTank().getFluidAmount() == 0, "Empty tank packet retained old water");

        int rgb = renderWater(minecraft, clientTank);
        result.addProperty("water_bar_rgb", String.format("%06X", rgb));
        int red = (rgb >>> 16) & 255;
        int green = (rgb >>> 8) & 255;
        int blue = rgb & 255;
        require(metalFloor && portableFloor, "Inset tank culled the floor beneath its exposed corners");
        require(blue > red + 20 && blue > green + 10, "Water gauge is not blue: " + String.format("%06X", rgb));
    }

    private static boolean floorVisible(final BlockState tank) {
        BlockGetter world = new BlockGetter() {
            @Override public int getHeight() { return 256; }
            @Override public int getMinBuildHeight() { return 0; }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) {
                return pos.equals(BlockPos.ZERO.above()) ? tank : net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState();
            }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        };
        return Block.shouldRenderFace(net.minecraft.world.level.block.Blocks.GRASS_BLOCK.defaultBlockState(), world, BlockPos.ZERO, Direction.UP, BlockPos.ZERO.above());
    }

    private static int renderWater(Minecraft minecraft, MetalTankTileEntity tile) {
        FilteredFluidTankGUI definition = new FilteredFluidTankGUI(new ResourceLocation("basepower", "textures/gui/container/fluid_metal_tank_gui.png"));
        final int width = 128, height = 96;
        Inventory inventory = new Inventory(null);
        SimpleMachineGUI.Container container = definition.createMenu(1, inventory, tile);
        require(container.getType() == MachineGUIRegistry.TYPE && MachineGUIRegistry.TYPE != null,
                "Tank GUI is not backed by the registered native container type");
        SimpleMachineGUI.GUIContainer screen = container.createScreen(inventory, new net.minecraft.network.chat.TextComponent("Tank probe"));
        screen.init(minecraft, width, height);
        require(screen.getMinecraft() == minecraft, "Native tank screen was not initialized");
        tile.getTank().setFluid(new cyano.poweradvantage.api.fluid.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 10000));
        RenderTarget framebuffer = new com.mojang.blaze3d.pipeline.TextureTarget(width, height, true, Minecraft.ON_OSX);
        framebuffer.bindWrite(true);
        NativeGuiProjection projection = new NativeGuiProjection(width, height, -1, 1, 0);
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
        RenderSystem.enableTexture();
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        try {
            RenderSystem.clearColor(0F, 0F, 0F, 1F);
            RenderSystem.clear(GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            definition.drawGUIDecorations(tile, screen, 0, 0, 0F);
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            RenderSystem.readPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int index = ((height - y - 1) * width + x) * 4;
                    image.setRGB(x, y, (pixels.get(index) & 255) << 16
                            | (pixels.get(index + 1) & 255) << 8 | pixels.get(index + 2) & 255);
                }
            }
            ImageIO.write(image, "png", new File("tank-water-gauge.png"));
            return image.getRGB(88, 39) & 0xFFFFFF;
        } catch (IOException failure) {
            throw new IllegalStateException("Could not retain the native tank gauge image", failure);
        } finally {
            projection.close();
            framebuffer.destroyBuffers();
            minecraft.getMainRenderTarget().bindWrite(true);
            RenderSystem.enableDepthTest();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
