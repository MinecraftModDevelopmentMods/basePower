package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.block.entity.BlockEntity;
import zone.moddev.mc.basepower.init.Fluids;
import zone.moddev.mc.basepower.init.Items;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Exercises the native fluid renderer, not just oil's particle-only block model. */
final class OilRenderProbe {
    private OilRenderProbe() {}

    static void verify(Minecraft minecraft, JsonObject result) {
        // Before a world/server is opened, vanilla's fluid tags have not been synchronized.
        Map<TagKey<Fluid>, List<Holder<Fluid>>> previous = fluidTags();
        Map<TagKey<Fluid>, List<Holder<Fluid>>> synthetic = new HashMap<>(previous);
        synthetic.put(FluidTags.WATER, List.of(net.minecraft.world.level.material.Fluids.WATER.builtInRegistryHolder(),
                net.minecraft.world.level.material.Fluids.FLOWING_WATER.builtInRegistryHolder()));
        synthetic.put(FluidTags.LAVA, List.of(net.minecraft.world.level.material.Fluids.LAVA.builtInRegistryHolder(),
                net.minecraft.world.level.material.Fluids.FLOWING_LAVA.builtInRegistryHolder()));
        BuiltInRegistries.FLUID.bindTags(synthetic);
        try {
        requireBucket(Items.crude_oil_bucket, Fluids.crude_oil);
        requireBucket(Items.refined_oil_bucket, Fluids.refined_oil);
        result.addProperty("oil_bucket_identities", true);

        JsonArray cases = new JsonArray();
        result.add("fluid_rendering", cases);
        boolean valid = true;
        valid &= check(minecraft, Fluids.crude_oil.defaultFluidState(), "basepower:blocks/crude_oil_still", false, cases);
        valid &= check(minecraft, Fluids.flowing_crude_oil.getFlowing(5, false), "basepower:blocks/crude_oil_flow", true, cases);
        valid &= check(minecraft, Fluids.refined_oil.defaultFluidState(), "basepower:blocks/refined_oil_still", false, cases);
        valid &= check(minecraft, Fluids.flowing_refined_oil.getFlowing(5, false), "basepower:blocks/refined_oil_flow", true, cases);
        valid &= check(minecraft, net.minecraft.world.level.material.Fluids.WATER.defaultFluidState(), "minecraft:block/water_still", false, cases);
        valid &= check(minecraft, net.minecraft.world.level.material.Fluids.LAVA.defaultFluidState(), "minecraft:block/lava_still", false, cases);
        if (!valid) throw new IllegalStateException("Native fluid renderer used the wrong sprite or tint: " + cases);
        } finally {
            BuiltInRegistries.FLUID.resetTags();
            BuiltInRegistries.FLUID.bindTags(previous);
            require(fluidTags().equals(previous), "Synthetic fluid tag membership was not restored");
        }
    }

    private static Map<TagKey<Fluid>, List<Holder<Fluid>>> fluidTags() {
        Map<TagKey<Fluid>, List<Holder<Fluid>>> tags = new HashMap<>();
        BuiltInRegistries.FLUID.getTags().forEach(tag -> {
            List<Holder<Fluid>> values = tag.getSecond().stream().toList();
            if (!values.isEmpty()) tags.put(tag.getFirst(), values);
        });
        return tags;
    }

    private static void requireBucket(net.minecraft.world.item.Item bucket, Fluid expected) {
        net.minecraftforge.fluids.FluidStack contents = zone.moddev.mc.basepower.util.FluidContainerHelper
                .getFluidContained(new ItemStack(bucket))
                .orElseThrow(() -> new IllegalStateException("Bucket cannot supply a fluid container: " + ForgeRegistries.ITEMS.getKey(bucket)));
        if (!(bucket instanceof BucketItem) || ((BucketItem) bucket).getFluid() != expected
                || expected.getBucket() != bucket || contents.getFluid() != expected || contents.getAmount() != 1000) {
            throw new IllegalStateException("Wrong bucket fluid: " + ForgeRegistries.ITEMS.getKey(bucket));
        }
    }

    private static boolean check(Minecraft minecraft, FluidState state, String texture, boolean flowing, JsonArray cases) {
        // A tiny read-only neighbourhood supplies a slope for flowing sprites; no save is opened.
        BlockAndTintGetter world = new BlockAndTintGetter() {
            @Override public int getHeight() { return 256; }
            @Override public int getMinBuildHeight() { return 0; }
            @Override public float getShade(Direction side, boolean shade) { return 1.0F; }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return blockAt(state, flowing, pos); }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getBrightness(LightLayer type, BlockPos pos) { return 15; }
            @Override public LevelLightEngine getLightEngine() { throw new UnsupportedOperationException("Synthetic light is supplied by getLightFor"); }
            @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
                return 0x3F76E4; // Vanilla plains water tint for this read-only witness.
            }
        };
        BufferBuilder buffer = new BufferBuilder(4096);
        buffer.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        minecraft.getBlockRenderer().renderLiquid(
                BlockPos.ZERO, world, buffer, world.getBlockState(BlockPos.ZERO), state);
        BufferBuilder.RenderedBuffer built = buffer.end();
        int vertices = built.drawState().vertexCount();
        TextureAtlasSprite sprite = minecraft.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(new ResourceLocation(texture));
        require(vertices >= 4, "Native fluid renderer emitted no top face for " + state);
        ByteBuffer data = built.vertexBuffer();
        int uvOffset = elementOffset(VertexFormatElement.Usage.UV, 0);
        float u = data.getFloat(uvOffset);
        float v = data.getFloat(uvOffset + 4);
        int colorOffset = elementOffset(VertexFormatElement.Usage.COLOR, 0);
        int rgb = (data.get(colorOffset) & 255) << 16 | (data.get(colorOffset + 1) & 255) << 8 | data.get(colorOffset + 2) & 255;
        boolean correctSprite = !"missingno".equals(sprite.contents().name().getPath())
                && u >= sprite.getU0() && u <= sprite.getU1() && v >= sprite.getV0() && v <= sprite.getV1();
        boolean correctTint = !(state.getType() instanceof Fluids.PowerFluid) || rgb == 0xFFFFFF;
        JsonObject entry = new JsonObject();
        entry.addProperty("fluid", ForgeRegistries.FLUIDS.getKey(state.getType()).toString());
        entry.addProperty("block", ForgeRegistries.BLOCKS.getKey(state.createLegacyBlock().getBlock()).toString());
        entry.addProperty("expected_sprite", texture);
        entry.addProperty("correct_sprite", correctSprite);
        entry.addProperty("vertex_rgb", String.format("%06X", rgb));
        entry.addProperty("vertices", vertices);
        cases.add(entry);
        built.release();
        return correctSprite && correctTint;
    }

    private static int elementOffset(VertexFormatElement.Usage usage, int index) {
        for (int i = 0; i < DefaultVertexFormat.BLOCK.getElements().size(); i++) {
            VertexFormatElement element = DefaultVertexFormat.BLOCK.getElements().get(i);
            if (element.getUsage() == usage && element.getIndex() == index) return DefaultVertexFormat.BLOCK.getOffset(i);
        }
        throw new IllegalStateException("Native block vertex format lacks " + usage + " " + index);
    }

    private static BlockState blockAt(FluidState state, boolean flowing, BlockPos pos) {
        if (BlockPos.ZERO.equals(pos)) return state.createLegacyBlock();
        if (flowing && BlockPos.ZERO.east().equals(pos)) {
            return ((FlowingFluid) state.getType()).getFlowing(3, false).createLegacyBlock();
        }
        return (pos.getY() < 0 ? net.minecraft.world.level.block.Blocks.STONE : net.minecraft.world.level.block.Blocks.AIR).defaultBlockState();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
