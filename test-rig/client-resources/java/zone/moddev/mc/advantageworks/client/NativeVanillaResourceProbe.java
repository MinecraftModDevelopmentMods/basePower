package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;

/** Reads actual vanilla pack roots during preparation, before any atlas upload. */
final class NativeVanillaResourceProbe implements PreparableReloadListener {
    private static final AtomicInteger RELOADS = new AtomicInteger();

    static void register() {
        RegisterClientReloadListenersEvent.BUS.addListener(event ->
                event.registerReloadListener(new NativeVanillaResourceProbe()));
    }

    @Override
    public CompletableFuture<Void> reload(SharedState reload, Executor prepare,
            PreparationBarrier barrier, Executor apply) {
        return CompletableFuture.supplyAsync(() -> capture(reload.resourceManager()), prepare)
                .thenCompose(barrier::wait).thenAcceptAsync(unused -> {}, apply);
    }

    private static JsonObject capture(ResourceManager resources) {
        JsonObject result = new JsonObject();
        result.addProperty("protocol", "NATIVE65_VANILLA_RESOURCE_ROOTS_V1");
        result.addProperty("phase", "reload-preparation-before-atlas-upload");
        JsonArray rows = new JsonArray();
        result.add("resources", rows);
        boolean present = true;
        try {
            for (String name : new String[]{"minecraft:textures/block/stone.png", "minecraft:atlases/blocks.json"}) {
                Identifier id = Identifier.parse(name);
                JsonObject row = new JsonObject();
                row.addProperty("resource", name);
                rows.add(row);
                var vanilla = Minecraft.getInstance().getVanillaPackResources()
                        .getResource(PackType.CLIENT_RESOURCES, id);
                JsonObject direct = new JsonObject();
                direct.addProperty("present", vanilla != null);
                row.add("vanilla", direct);
                if (vanilla != null) {
                    try (InputStream stream = vanilla.get()) { describe(direct, stream.readAllBytes()); }
                }
                JsonArray merged = new JsonArray();
                row.add("merged", merged);
                for (var resource : resources.getResourceStack(id)) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("pack", resource.sourcePackId());
                    try (InputStream stream = resource.open()) { describe(entry, stream.readAllBytes()); }
                    merged.add(entry);
                }
                present &= vanilla != null && !merged.isEmpty();
            }
            result.addProperty("status", present ? "PASS" : "FAIL");
            Path folder = Path.of("vanilla-resource-controls");
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(String.format("control-%02d.json", RELOADS.incrementAndGet())),
                    result.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            if (!present) throw new IllegalStateException("Actual vanilla resource roots are missing stone or blocks atlas");
            return result;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot retain actual vanilla resource-root control", failure);
        }
    }

    private static void describe(JsonObject target, byte[] bytes) {
        try {
            target.addProperty("bytes", bytes.length);
            target.addProperty("sha256", HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
