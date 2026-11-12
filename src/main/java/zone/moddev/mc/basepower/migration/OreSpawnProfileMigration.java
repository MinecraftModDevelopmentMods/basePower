package zone.moddev.mc.basepower.migration;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import cyano.poweradvantage.api.migration.LegacyOreSpawnProfileMigration;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;
import zone.moddev.mc.basepower.PowerAdvantage;

/** Runs output migrations before OreSpawn reads global or selected-world profiles. */
public final class OreSpawnProfileMigration {
    private static final Map<String, JsonObject> DEFAULTS = new LinkedHashMap<>();

    private OreSpawnProfileMigration() {}

    public static void registerPowerProvider(Path configDirectory) throws IOException {
        String resource = "/data/basepower/orespawn/provider.json";
        try (InputStream input = OreSpawnProfileMigration.class.getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing owned OreSpawn defaults: " + resource);
            try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                registerProvider(configDirectory, JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
    }

    /** Called during mod construction, using pure JSON rather than unbound block holders. */
    public static synchronized void registerProvider(Path configDirectory, JsonObject defaults) throws IOException {
        String owner = defaults.get("provider_modid").getAsString();
        if (!owner.equals("basepower") && !owner.equals("baseelectric")) {
            throw new IllegalArgumentException("Not an Advantage generation provider: " + owner);
        }
        JsonObject previous = DEFAULTS.putIfAbsent(owner, defaults.deepCopy());
        if (previous != null && !previous.equals(defaults)) {
            throw new IllegalStateException("Conflicting OreSpawn migration defaults for " + owner);
        }
        migrate(configDirectory.resolve("orespawn-worldgen.json"), defaults);
        migrate(configDirectory.resolve(owner + "-orespawn.json"), defaults);
    }

    /** The caller already owns the selected world's storage lock; no preview path calls this. */
    public static synchronized void migrateWorld(Path worldDirectory) throws IOException {
        Path profile = worldDirectory.resolve("serverconfig").resolve("orespawn-worldgen.json");
        for (JsonObject defaults : DEFAULTS.values()) migrate(profile, defaults);
    }

    private static void migrate(Path path, JsonObject defaults) throws IOException {
        LegacyOreSpawnProfileMigration.Result result = LegacyOreSpawnProfileMigration.migrate(
                path, defaults, OreSpawnProfileMigration::declaredTarget);
        if (result.status() == LegacyOreSpawnProfileMigration.Status.REFUSED) {
            throw new IOException("OreSpawn profile needs review before terrain generation: " + path
                    + "; " + result.plan().getProblems() + "; report: " + result.reportFile());
        }
        if (result.wasMigrated()) {
            PowerAdvantage.LOGGER.info("Preserved OreSpawn placement settings while migrating {} profile entries in '{}'; report: {}",
                    result.plan().getChanges().size(), path, result.reportFile());
        }
    }

    static boolean declaredTarget(String name) {
        if (name.equals("minecraft:nether_quartz_ore")) return true;
        Identifier id = Identifier.tryParse(name);
        return id != null && LegacyMigrationApi.blockStateCodecs().containsKey(id);
    }
}
