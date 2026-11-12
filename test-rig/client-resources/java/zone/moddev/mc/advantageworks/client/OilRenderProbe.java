package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.Minecraft;
import com.mojang.blaze3d.vertex.BufferBuilder;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.Identifier;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.world.level.CardinalLighting;
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

/** Exercises the native fluid renderer, not just oil's particle-only block model. */
final class OilRenderProbe {
    private OilRenderProbe() {}

    static void verify(Minecraft minecraft, JsonObject result) {
        require(minecraft.level != null && minecraft.player != null, "Fluid models require actual native login");
        Map<TagKey<Fluid>, List<Holder<Fluid>>> previous = fluidTags();
        require(previous.containsKey(FluidTags.WATER) && previous.containsKey(FluidTags.LAVA)
                && previous.get(FluidTags.WATER).contains(net.minecraft.world.level.material.Fluids.WATER.builtInRegistryHolder())
                && previous.get(FluidTags.LAVA).contains(net.minecraft.world.level.material.Fluids.LAVA.builtInRegistryHolder()),
                "Actual login did not bind native fluid tags");
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
        require(fluidTags().equals(previous), "Fluid geometry probe changed actual native tags");
        result.addProperty("fluid_tag_context", "ACTUAL_LOGIN_UNMODIFIED_NATIVE_TAGS");
    }

    private static Map<TagKey<Fluid>, List<Holder<Fluid>>> fluidTags() {
        Map<TagKey<Fluid>, List<Holder<Fluid>>> tags = new HashMap<>();
        BuiltInRegistries.FLUID.getTags().forEach(tag -> {
            List<Holder<Fluid>> values = tag.stream().toList();
            tags.put(tag.key(), values);
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
            @Override public int getMinY() { return 0; }
            @Override public CardinalLighting cardinalLighting() { return new CardinalLighting(1, 1, 1, 1, 1, 1); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
            @Override public BlockState getBlockState(BlockPos pos) { return blockAt(state, flowing, pos); }
            @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public int getBrightness(LightLayer type, BlockPos pos) { return 15; }
            @Override public LevelLightEngine getLightEngine() { throw new UnsupportedOperationException("Synthetic light is supplied by getLightFor"); }
            @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) {
                return 0x3F76E4; // Vanilla plains water tint for this read-only witness.
            }
        };
        try (var storage = new com.mojang.blaze3d.vertex.ByteBufferBuilder(4096)) {
        BufferBuilder buffer = new BufferBuilder(storage, com.mojang.blaze3d.PrimitiveTopology.QUADS, DefaultVertexFormat.BLOCK);
        var models = minecraft.getModelManager().getFluidStateModelSet();
        var nativeModel = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(state)
                .getModel(state, world, BlockPos.ZERO, models.get(state));
        require(nativeModel != null, "Native fluid extensions returned no model: " + state);
        if (state.getType() instanceof Fluids.PowerFluid) {
            require(nativeModel.layer() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT,
                    "Oil selected a non-translucent chunk layer");
        }
        new FluidRenderer(models).tesselate(world, BlockPos.ZERO, layer -> {
            require(layer == nativeModel.layer(), "Native fluid renderer selected the wrong model layer");
            return buffer;
        }, world.getBlockState(BlockPos.ZERO), state);
        try (var built = buffer.buildOrThrow()) {
        int vertices = built.drawState().vertexCount();
        TextureAtlasSprite sprite = minecraft.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS)
                .getSprite(Identifier.parse(texture));
        require(vertices >= 4, "Native fluid renderer emitted no top face for " + state);
        ByteBuffer data = built.vertexBuffer();
        int uvOffset = elementOffset(DefaultVertexFormat.UV0_SEMANTIC_NAME);
        float u = data.getFloat(uvOffset);
        float v = data.getFloat(uvOffset + 4);
        int colorOffset = elementOffset(DefaultVertexFormat.COLOR_SEMANTIC_NAME);
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
        return correctSprite && correctTint;
        }
        }
    }

    private static int elementOffset(String required) {
        VertexFormatElement element = DefaultVertexFormat.BLOCK.getElement(required);
        if (element == null) throw new IllegalStateException("Native block vertex format lacks " + required);
        return element.offset();
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
