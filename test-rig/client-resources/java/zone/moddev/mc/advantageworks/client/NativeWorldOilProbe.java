package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/** Native LevelRenderer frames in the disposable copied world, including deliberately wrong world states. */
final class NativeWorldOilProbe {
    private static final int Y = 6, Z = 510;
    private static final int[] X = {504, 510, 516};
    private static final String[] NAMES = {"crude-oil", "refined-oil", "water-control"};
    private static final Fluid[] OILS = {zone.moddev.mc.basepower.init.Fluids.crude_oil,
            zone.moddev.mc.basepower.init.Fluids.refined_oil, Fluids.WATER};
    private final JsonObject report = new JsonObject();
    private final Map<String, BufferedImage> images = new LinkedHashMap<>();
    private final JsonObject frames = new JsonObject();
    private final Map<String, Integer> spriteIdentities = new LinkedHashMap<>();
    private CompletableFuture<Void> serverWork;
    private CompletableFuture<Void> reload;
    private String phase = "NEW";
    private int deadline, stableSince = -1;
    private boolean originalHideGui;

    JsonObject report() { return report.deepCopy(); }

    JsonObject tick(Minecraft minecraft, int tick) {
        try {
            if (phase.equals("DONE")) return report();
            require(minecraft.level != null && minecraft.player != null && minecraft.getSingleplayerServer() != null,
                    "Actual oil scene requires a connected integrated world");
            require(minecraft.getSingleplayerServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toRealPath()
                    .equals(Path.of("saves/AdvantageClientResources").toRealPath()), "Oil scene cannot write outside the disposable copy");
            if (phase.equals("NEW")) {
                report.addProperty("protocol", "NATIVE66_LEVEL_RENDERER_OIL_RELOAD_V1");
                report.addProperty("status", "RUNNING"); report.add("frames", frames);
                report.addProperty("scope", "ACTUAL_LEVEL_RENDERER_NOT_GUI_OR_SYNTHETIC_WORLD");
                originalHideGui = minecraft.gui.hud.isHidden(); setHudHidden(minecraft, true);
                require(minecraft.options.mipmapLevels().get() == 4, "World oil proof requires default mipmap level4");
                report.addProperty("mipmapLevel", 4);
                serverWork = scene(minecraft, "oil", true);
                phase = "ORIGINAL"; deadline = tick + 1200;
            }
            require(tick < deadline, "Native world oil scene timed out during " + phase);
            if (reload != null) { if (!reload.isDone()) return null; reload.join(); }
            if (!serverWork.isDone()) return null;
            serverWork.join();
            String expected = phase.equals("EMPTY") ? "empty" : phase.equals("WRONG_WATER") ? "water" : "oil";
            if (!ready(minecraft, expected)) { stableSince = -1; return null; }
            if (stableSince < 0) { stableSince = tick; return null; }
            if (tick - stableSince < 30) return null;
            stableSince = -1;
            capture(minecraft, phase.toLowerCase(java.util.Locale.ROOT));
            switch (phase) {
                case "ORIGINAL" -> {
                    report.add("modelsBeforeReload", models(minecraft, true));
                    serverWork = scene(minecraft, "empty", false); phase = "EMPTY";
                }
                case "EMPTY" -> { serverWork = scene(minecraft, "water", false); phase = "WRONG_WATER"; }
                case "WRONG_WATER" -> { serverWork = scene(minecraft, "oil", false); phase = "RESTORED"; }
                case "RESTORED" -> { reload = minecraft.reloadResourcePacks(); phase = "AFTER_RELOAD"; }
                case "AFTER_RELOAD" -> {
                    report.add("modelsAfterReload", models(minecraft, false));
                    JsonObject geometry = new JsonObject(); OilRenderProbe.verify(minecraft, geometry);
                    report.add("nativeGeometryAfterReload", geometry);
                    JsonArray controls = compareControls(); report.add("pixelControls", controls);
                    report.addProperty("status", "PASS"); phase = "DONE";
                    setHudHidden(minecraft, originalHideGui);
                    Files.writeString(Path.of("world-oil-evidence/result.json"), report.toString(), StandardOpenOption.CREATE_NEW);
                    return report();
                }
                default -> throw new IllegalStateException("Unknown native world oil phase " + phase);
            }
            return null;
        } catch (IOException | RuntimeException | LinkageError | AssertionError failure) {
            setHudHidden(minecraft, originalHideGui);
            report.addProperty("status", "FAIL"); report.addProperty("phase", phase); report.addProperty("error", failure.toString());
            throw new IllegalStateException("Native world oil proof failed in " + phase, failure);
        }
    }

    private CompletableFuture<Void> scene(Minecraft minecraft, String mode, boolean initialize) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        var server = minecraft.getSingleplayerServer();
        server.execute(() -> {
            try {
                var level = server.getLevel(Level.OVERWORLD);
                require(level != null, "Missing actual Overworld");
                if (initialize) {
                    for (int x = 502; x <= 521; x++) for (int z = 508; z <= 515; z++) {
                        level.setBlock(new BlockPos(x, Y - 1, z), Blocks.CONCRETE.white().defaultBlockState(), 3);
                        level.setBlock(new BlockPos(x, Y, z), Blocks.CONCRETE.white().defaultBlockState(), 3);
                    }
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "time set noon");
                    server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "weather clear");
                    var player = server.getPlayerList().getPlayerByName("AdvantageProbe");
                    require(player != null, "Missing actual logged-in probe player");
                    player.setGameMode(GameType.SPECTATOR);
                    require(player.teleportTo(level, 512.5, 14.0, 527.0, Set.of(), 180F, 35F, true), "Native scene teleport failed");
                }
                for (int pool = 0; pool < 3; pool++) for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
                    var state = pool == 2 || mode.equals("water") ? Fluids.WATER.defaultFluidState().createLegacyBlock()
                            : mode.equals("empty") ? Blocks.AIR.defaultBlockState() : OILS[pool].defaultFluidState().createLegacyBlock();
                    level.setBlock(new BlockPos(X[pool] + x, Y, Z + z), state, 3);
                }
                result.complete(null);
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    private static boolean compiledAndVisible(Minecraft minecraft, BlockPos pos) {
        long fadeDuration = net.minecraft.util.Util.toMillis(minecraft.options.chunkSectionFadeInTime().get());
        return minecraft.levelRenderer.isSectionCompiledAndVisible(pos, fadeDuration);
    }

    private boolean ready(Minecraft minecraft, String mode) {
        if (minecraft.gui.overlay() != null || minecraft.gui.screen() != null || !minecraft.level.dimension().equals(Level.OVERWORLD)) return false;
        if (minecraft.player.position().distanceToSqr(512.5, 14, 527) > 0.0001) return false;
        for (int pool = 0; pool < 3; pool++) for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
            BlockPos pos = new BlockPos(X[pool] + x, Y, Z + z);
            Fluid expected = pool == 2 || mode.equals("water") ? Fluids.WATER : mode.equals("empty") ? Fluids.EMPTY : OILS[pool];
            if (minecraft.level.getFluidState(pos).getType() != expected || !compiledAndVisible(minecraft, pos)) return false;
        }
        return !minecraft.levelRenderer.visibleSections().isEmpty();
    }

    private JsonArray models(Minecraft minecraft, boolean before) {
        JsonArray models = new JsonArray();
        var states = minecraft.getModelManager().getFluidStateModelSet();
        for (int pool = 0; pool < 2; pool++) {
            BlockPos pos = new BlockPos(X[pool] + 1, Y, Z + 1);
            var state = minecraft.level.getFluidState(pos);
            var model = net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(state).getModel(state, minecraft.level, pos, states.get(state));
            String name = pool == 0 ? "crude_oil" : "refined_oil";
            JsonObject row = new JsonObject(); row.addProperty("fluid", "basepower:" + name);
            require(model.layer() == net.minecraft.client.renderer.chunk.ChunkSectionLayer.TRANSLUCENT, "Actual-world oil model is not translucent");
            for (boolean flow : new boolean[]{false, true}) {
                String key = flow ? "flowing" : "still";
                var sprite = (flow ? model.flowingMaterial() : model.stillMaterial()).sprite();
                Identifier expected = Identifier.parse("basepower:blocks/" + name + (flow ? "_flow" : "_still"));
                var current = minecraft.getAtlasManager().getAtlasOrThrow(net.minecraft.data.AtlasIds.BLOCKS).getSprite(expected);
                require(sprite == current && sprite.contents().name().equals(expected), "Actual-world oil model retained a stale/missing sprite");
                int identity = System.identityHashCode(sprite);
                String identityKey = name + "/" + key;
                if (before) spriteIdentities.put(identityKey, identity);
                else require(spriteIdentities.get(identityKey) != identity, "Resource reload did not replace the oil atlas sprite");
                row.addProperty(key + "Sprite", expected.toString()); row.addProperty(key + "Identity", identity);
            }
            row.addProperty("layer", "TRANSLUCENT"); models.add(row);
        }
        return models;
    }

    private void capture(Minecraft minecraft, String name) throws IOException {
        BufferedImage image = NativeGuiProjection.read(minecraft.gameRenderer.mainRenderTarget());
        Path folder = Path.of("world-oil-evidence"); Files.createDirectories(folder);
        Path png = folder.resolve(name + ".png"), raw = folder.resolve(name + ".rgba");
        try (var output = Files.newOutputStream(png, StandardOpenOption.CREATE_NEW)) { require(ImageIO.write(image, "png", output), "No world PNG encoder"); }
        try (var output = new java.io.BufferedOutputStream(Files.newOutputStream(raw, StandardOpenOption.CREATE_NEW))) {
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
                int color = image.getRGB(x, y); output.write(color >> 16 & 255); output.write(color >> 8 & 255); output.write(color & 255); output.write(255);
            }
        }
        images.put(name, image);
        JsonObject frame = new JsonObject(); frame.addProperty("width", image.getWidth()); frame.addProperty("height", image.getHeight());
        frame.add("png", artifact(png)); frame.add("rgba", artifact(raw)); frame.addProperty("fenceCompleted", true);
        frame.addProperty("renderer", minecraft.levelRenderer.getClass().getName());
        frame.addProperty("dimension", minecraft.level.dimension().identifier().toString());
        frame.addProperty("hudHidden", minecraft.gui.hud.isHidden());
        frame.addProperty("renderedSections", minecraft.levelRenderer.visibleSections().size());
        JsonArray camera = new JsonArray(); Vec3 position = minecraft.gameRenderer.mainCamera().position();
        camera.add(position.x); camera.add(position.y); camera.add(position.z); frame.add("camera", camera);
        JsonArray matrix = new JsonArray(); float[] values = new float[16];
        minecraft.gameRenderer.mainCamera().getViewRotationProjectionMatrix(new org.joml.Matrix4f()).get(values);
        for (float value : values) matrix.add(value); frame.add("viewRotationProjection", matrix);
        JsonArray pools = new JsonArray();
        for (int pool = 0; pool < 3; pool++) {
            JsonObject row = new JsonObject(); row.addProperty("pool", NAMES[pool]); JsonArray samples = new JsonArray();
            row.addProperty("fluid", net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(
                    minecraft.level.getFluidState(new BlockPos(X[pool] + 1, Y, Z + 1)).getType()).toString());
            row.addProperty("compiledAndVisible", compiledAndVisible(minecraft, new BlockPos(X[pool] + 1, Y, Z + 1)));
            for (int x = 0; x < 4; x++) for (int z = 0; z < 4; z++) {
                Vec3 point = new Vec3(X[pool] + .75 + x * .75, Y + 8D / 9D, Z + .75 + z * .75);
                Vec3 projected = minecraft.gameRenderer.projectPointToScreen(point);
                int px = (int) ((projected.x * .5 + .5) * image.getWidth());
                int py = (int) ((.5 - projected.y * .5) * image.getHeight());
                require(px >= 2 && py >= 2 && px < image.getWidth() - 2 && py < image.getHeight() - 2 && projected.z > -1 && projected.z < 1, "Oil pool projected outside the actual world framebuffer");
                JsonArray sample = new JsonArray(); sample.add(point.x); sample.add(point.y); sample.add(point.z); sample.add(px); sample.add(py); samples.add(sample);
            }
            row.add("samples", samples); pools.add(row);
        }
        frame.add("pools", pools); frames.add(name, frame);
    }

    private JsonArray compareControls() {
        JsonArray rows = new JsonArray();
        for (String actual : new String[]{"original", "after_reload"}) for (int pool = 0; pool < 3; pool++) {
            JsonObject row = new JsonObject(); row.addProperty("actual", actual); row.addProperty("pool", NAMES[pool]);
            for (String control : new String[]{"empty", "wrong_water"}) {
                int different = 0, checked = 0;
                for (var item : frames.getAsJsonObject(actual).getAsJsonArray("pools").get(pool).getAsJsonObject().getAsJsonArray("samples")) {
                    JsonArray sample = item.getAsJsonArray(); int x = sample.get(3).getAsInt(), y = sample.get(4).getAsInt();
                    for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
                        int a = images.get(actual).getRGB(x + dx, y + dy), b = images.get(control).getRGB(x + dx, y + dy);
                        int distance = Math.abs((a >> 16 & 255) - (b >> 16 & 255)) + Math.abs((a >> 8 & 255) - (b >> 8 & 255)) + Math.abs((a & 255) - (b & 255));
                        if (distance > (pool == 2 ? 32 : 64)) different++; checked++;
                    }
                }
                require(pool == 2 ? different <= checked / 10 : different >= checked / 2, "Native world oil empty/wrong-fluid control failed: " + actual + "/" + NAMES[pool] + "/" + control);
                row.addProperty(control + "Different", different); row.addProperty("samples", checked);
            }
            rows.add(row);
        }
        return rows;
    }

    private static JsonObject artifact(Path path) throws IOException {
        JsonObject row = new JsonObject(); row.addProperty("file", path.toString().replace('\\', '/'));
        try { row.addProperty("sha256", HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        return row;
    }
    private static void setHudHidden(Minecraft minecraft, boolean hidden) {
        if (minecraft.gui.hud.isHidden() != hidden) minecraft.gui.hud.toggle();
        require(minecraft.gui.hud.isHidden() == hidden, "Native HUD did not change visibility");
    }

    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
