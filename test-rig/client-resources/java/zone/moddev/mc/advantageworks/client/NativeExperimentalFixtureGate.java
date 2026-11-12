package zone.moddev.mc.advantageworks.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** One approved native warning action for an authenticated, completed native broad fixture copy. */
final class NativeExperimentalFixtureGate {
    static final String PROTOCOL = "NATIVE65_SEALED_EXPERIMENTAL_FIXTURE_SKIP_V2";
    static final String SCOPE = "COMPLETED_NATIVE65_BROAD_PACKAGED_CURRENT_COPY";
    static final String TITLE = "selectWorld.backupQuestion.experimental";
    static final String SKIP = "selectWorld.backupJoinSkipButton";
    private Screen observed;
    private int firstTick;
    private boolean used;
    private JsonObject action;

    static boolean exactTranslation(Component component, String key) {
        return component.getContents() instanceof TranslatableContents contents && key.equals(contents.getKey())
                && contents.getArgs().length == 0 && contents.getFallback() == null && component.getSiblings().isEmpty();
    }

    static Button select(Class<?> screenClass, Component title, List<?> children) {
        if (screenClass != BackupConfirmScreen.class || !exactTranslation(title, TITLE)) return null;
        List<Button> matches = children.stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(button -> button.visible && button.active && exactTranslation(button.getMessage(), SKIP)).toList();
        require(matches.size() == 1, "Experimental fixture warning must have one visible active native skip button");
        return matches.getFirst();
    }

    void tick(Minecraft minecraft, int tick, JsonObject readiness) {
        Screen screen = minecraft.gui.screen();
        if (screen == null || screen.getClass() != BackupConfirmScreen.class || !exactTranslation(screen.getTitle(), TITLE)) return;
        if (used) {
            require(screen == observed, "Repeated experimental warning after the once-only fixture action");
            return;
        }
        if (minecraft.gui.overlay() != null) { observed = screen; firstTick = tick; return; }
        if (screen != observed) { observed = screen; firstTick = tick; return; }
        if (tick - firstTick < 20) return;
        try {
            Path folder = Path.of("login-readiness");
            Files.createDirectories(folder);
            Path screenshot = folder.resolve("experimental-before-action.png");
            var image = NativeGuiProjection.read(minecraft.gameRenderer.mainRenderTarget());
            try (var output = Files.newOutputStream(screenshot, StandardOpenOption.CREATE_NEW)) {
                require(ImageIO.write(image, "png", output), "Missing native warning PNG writer");
            }
            action = new JsonObject();
            action.addProperty("protocol", PROTOCOL);
            action.addProperty("status", "SCREEN_RECORDED_ACTION_NOT_TAKEN");
            action.addProperty("screenClass", screen.getClass().getName());
            action.addProperty("titleKey", TITLE);
            action.addProperty("buttonKey", SKIP);
            action.addProperty("actionCount", 0);
            action.addProperty("key", InputConstants.KEY_RETURN);
            action.addProperty("scancode", 0); action.addProperty("modifiers", 0);
            action.addProperty("screenshot", screenshot.toString().replace('\\', '/'));
            action.addProperty("screenshotSha256", sha(screenshot));
            action.addProperty("width", image.getWidth()); action.addProperty("height", image.getHeight());
            action.addProperty("fenceCompleted", true);
            action.add("readinessBeforeAction", readiness.deepCopy());
            Files.writeString(folder.resolve("experimental-screen.json"), action.toString(), StandardOpenOption.CREATE_NEW);
            Button skip = select(screen.getClass(), screen.getTitle(), screen.children());
            require(minecraft.level == null && minecraft.player == null && minecraft.getConnection() == null
                    && minecraft.getSingleplayerServer() == null && !minecraft.hasSingleplayerServer() && minecraft.gui.overlay() == null,
                    "Experimental fixture action is only allowed before native server/login");
            JsonObject policy = verifyPolicy(Path.of(""));
            action.addProperty("status", "RECORDED_BEFORE_NATIVE_ACTION");
            action.add("profileSha256", policy.get("profileSha256"));
            action.addProperty("policySha256", sha(Path.of("client-fixture-policy.json")));
            action.add("sourceReceiptSha256", policy.get("sourceReceiptSha256"));
            action.addProperty("copiedWorld", policy.get("copiedWorld").getAsString());
            action.add("copiedLevelCustody", policy.getAsJsonObject("copiedLevelCustody"));
            Files.writeString(folder.resolve("experimental-before-action.json"), action.toString(), StandardOpenOption.CREATE_NEW);
            used = true;
            action.addProperty("actionCount", 1);
            skip.onPress(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
            action.addProperty("status", "NATIVE_SKIP_BUTTON_RETURNED");
            action.addProperty("onPressReturned", true);
            Files.writeString(folder.resolve("experimental-action.json"), action.toString(), StandardOpenOption.CREATE_NEW);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot authenticate or retain the sealed fixture warning action", failure);
        }
    }

    JsonObject report() {
        if (action != null) return action.deepCopy();
        JsonObject result = new JsonObject();
        result.addProperty("protocol", PROTOCOL); result.addProperty("status", "NOT_OBSERVED");
        result.addProperty("actionCount", 0);
        return result;
    }

    static JsonObject verifyPolicy(Path directory) throws IOException {
        Path runtime = directory.toRealPath();
        Path policyFile = runtime.resolve("client-fixture-policy.json");
        JsonObject policy = read(policyFile), input = read(runtime.resolve("client-input.json"));
        require(PROTOCOL.equals(policy.get("protocol").getAsString()) && "RUNNING".equals(input.get("status").getAsString()), "Unsealed fixture policy");
        require(SCOPE.equals(policy.get("eligibility").getAsString()), "Unapproved fixture scope");
        String profileSha = policy.get("profileSha256").getAsString();
        require(profileSha.matches("[0-9A-F]{64}") && profileSha.equals(input.get("profileSha256").getAsString()), "Unapproved fixture profile");
        JsonObject binding = input.getAsJsonObject("experimentalFixturePolicy");
        require("client-fixture-policy.json".equals(binding.get("file").getAsString())
                && sha(policyFile).equals(binding.get("sha256").getAsString()), "Fixture policy hash changed");
        Path profile = Path.of(policy.get("profilePath").getAsString());
        require(profileSha.equals(sha(profile)), "Frozen fixture profile changed");
        Path sourceReceipt = Path.of(input.get("sourceWorldReceipt").getAsString());
        require(sha(sourceReceipt).equals(policy.get("sourceReceiptSha256").getAsString()), "Unapproved source receipt");
        JsonObject receipt = read(sourceReceipt), profileData = read(profile);
        require("26.2".equals(profileData.get("minecraftVersion").getAsString())
                && "26.2-65.1.0".equals(profileData.getAsJsonObject("forge").get("version").getAsString())
                && "26.2".equals(receipt.get("minecraft").getAsString()) && "65.1.0".equals(receipt.get("forge").getAsString())
                && "PASS".equals(receipt.get("status").getAsString()) && receipt.get("inputsUnchanged").getAsBoolean()
                && receipt.get("seedUnchanged").getAsBoolean() && profileSha.equals(receipt.get("profileSha256").getAsString())
                && "PACKAGED_WALKABLE_WORKS_AND_TWELVE_DRILL_WITNESSES".equals(receipt.get("scope").getAsString()), "Source receipt is not an eligible completed native broad run");
        JsonObject completed = receipt.getAsJsonObject("profiles").getAsJsonObject("packaged-current");
        require("PASS".equals(completed.get("status").getAsString()) && "packaged-current".equals(completed.get("profile").getAsString()), "Incomplete broad fixture source");
        for (String phase : List.of("first", "initial", "operation", "persistence", "restarted"))
            require("PASS".equals(completed.getAsJsonObject("phases").getAsJsonObject(phase).get("status").getAsString()), "Unfinished broad source phase");
        Path source = Path.of(input.get("sourceWorld").getAsString()).toRealPath();
        require(source.equals(sourceReceipt.toAbsolutePath().getParent().resolve("packaged-current/AdvantageWorksMigration").normalize()), "Unapproved broad source-world route");
        Path copy = runtime.resolve("saves/AdvantageClientResources").toRealPath();
        require(copy.startsWith(runtime) && copy.equals(runtime.resolve("saves/AdvantageClientResources").normalize())
                && copy.equals(Path.of(policy.get("copiedWorld").getAsString()).toAbsolutePath().normalize()) && !copy.startsWith(source)
                && !source.startsWith(runtime) && !runtime.startsWith(source), "Fixture is not an independent disposable copied world");
        String levelSha = policy.get("copiedLevelSha256").getAsString();
        require(levelSha.matches("[0-9A-F]{64}") && levelSha.equals(sha(source.resolve("level.dat"))), "Unapproved source/copied level bytes");
        // Pinned BM6786 removes only this empty obsolete Forge index during getUnfixedDataTag.
        CompoundTag sourceLevel = NbtIo.readCompressed(source.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        require(sourceLevel.getCompound("Data").orElseThrow().getInt("DataVersion").orElseThrow() == 4903
                && "26.2".equals(sourceLevel.getCompound("Data").orElseThrow().getCompound("Version").orElseThrow().getString("Name").orElseThrow()), "Not a stopped native world");
        String copyTransform = verifyCopiedLevel(sourceLevel,
                NbtIo.readCompressed(copy.resolve("level.dat"), NbtAccounter.unlimitedHeap()));
        JsonObject copyCustody = new JsonObject();
        copyCustody.addProperty("initialSha256", levelSha);
        copyCustody.addProperty("observedSha256", sha(copy.resolve("level.dat")));
        copyCustody.addProperty("exactTypedNbtTransform", copyTransform);
        policy.add("copiedLevelCustody", copyCustody);
        Path ledgerFile = runtime.resolve("client-world-source-files.json");
        require(sha(ledgerFile).equals(input.get("worldSourceLedgerSha256").getAsString())
                && sha(ledgerFile).equals(policy.get("sourceLedgerSha256").getAsString()), "Source custody ledger changed");
        for (var row : read(ledgerFile).entrySet()) {
            Path path = source.resolve(row.getKey()).normalize();
            require(path.startsWith(source) && sha(path).equals(row.getValue().getAsString()), "Protected source world changed");
        }
        JsonObject expected = input.getAsJsonObject("runtimeJars");
        require(expected.size() == 7 && expected.has("advantageclientresources.jar"), "Fixture needs exactly six family jars plus the probe");
        require(profileData.getAsJsonArray("mods").size() == 6, "Wrong fixture family cardinality");
        Set<String> ids = new java.util.HashSet<>();
        for (var value : profileData.getAsJsonArray("mods")) {
            JsonObject mod = value.getAsJsonObject();
            String id = mod.get("id").getAsString();
            require(ids.add(id) && receipt.getAsJsonObject("jars").get(id).equals(mod.get("sha256"))
                    && completed.getAsJsonObject("expectedMods").get(id).equals(mod.get("version")), "Source broad family differs from profile");
            require(expected.has(mod.get("file").getAsString()) && expected.get(mod.get("file").getAsString()).equals(mod.get("sha256")), "Fixture family differs from the pinned profile");
        }
        require(ids.equals(Set.of("basepower", "basesteam", "baseelectric", "basemetals", "orespawn", "advantageworkstest"))
                && receipt.getAsJsonObject("jars").keySet().equals(ids), "Not the ordinary six-jar source family");
        try (var files = Files.list(runtime.resolve("mods"))) {
            Set<String> names = files.map(path -> path.getFileName().toString()).collect(Collectors.toSet());
            require(names.equals(expected.keySet()), "Unexpected installed fixture code");
        }
        for (var row : expected.entrySet()) require(sha(runtime.resolve("mods").resolve(row.getKey())).equals(row.getValue().getAsString()), "Installed fixture jar hash changed");
        return policy;
    }

    static String verifyCopiedLevel(CompoundTag source, CompoundTag observed) {
        if (source.equals(observed)) return "UNCHANGED_TYPED_ROOT";
        CompoundTag expected = source.copy();
        CompoundTag registries = expected.getCompound("fml").orElseThrow().getCompound("Registries").orElseThrow();
        CompoundTag index = registries.getCompound("minecraft:worldgen/biome").orElseThrow();
        require(index.keySet().equals(Set.of("aliases", "blocked", "ids", "overrides"))
                && index.get("aliases") instanceof ListTag aliases && aliases.isEmpty()
                && index.get("ids") instanceof ListTag ids && ids.isEmpty()
                && index.get("overrides") instanceof ListTag overrides && overrides.isEmpty()
                && index.get("blocked") instanceof IntArrayTag blocked && blocked.getAsIntArray().length == 0,
                "Fixture source does not have the exact approved empty obsolete biome index");
        registries.remove("minecraft:worldgen/biome");
        require(expected.equals(observed), "Copied world differs beyond the exact empty obsolete biome-index removal");
        return "ONLY_FML_LOWERCASE_EMPTY_BIOME_INDEX_REMOVED";
    }

    private static JsonObject read(Path path) throws IOException { return JsonParser.parseString(Files.readString(path)).getAsJsonObject(); }
    private static String sha(Path path) throws IOException {
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path), "Missing or aliased fixture input");
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
