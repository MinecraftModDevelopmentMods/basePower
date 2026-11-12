package cyano.poweradvantage.api.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Output-only migration; never renames rule IDs, provider manifests or policy references. */
public final class LegacyOreSpawnProfileMigration {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String FORMAT = "advantage-orespawn-profile-outputs-v1";
    private static final String ORDINARY = "orespawn:all_except_nether_end";
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final List<String> WRAPPERS = List.of("profile", "geology", "worldgen", "geology_profile", "worldgen_profile");

    private LegacyOreSpawnProfileMigration() {}

    /** Pure result. A refused plan exposes the original JSON, not a partially rewritten profile. */
    public static final class Plan {
        private final JsonObject json;
        private final List<String> changes;
        private final List<String> problems;
        private final List<String> warnings;

        private Plan(JsonObject json, List<String> changes, List<String> problems, List<String> warnings) {
            this.json = json.deepCopy();
            this.changes = List.copyOf(changes);
            this.problems = List.copyOf(problems);
            this.warnings = List.copyOf(warnings);
        }

        public JsonObject getJson() { return json.deepCopy(); }
        public List<String> getChanges() { return changes; }
        public List<String> getProblems() { return problems; }
        public List<String> getWarnings() { return warnings; }
        public boolean isRefused() { return !problems.isEmpty(); }
        public boolean isChanged() { return !isRefused() && !changes.isEmpty(); }
    }

    public enum Status { MISSING, UNCHANGED, REFUSED, MIGRATED }

    public record Result(Status status, Path backupFile, Path reportFile, Plan plan) {
        public boolean wasMigrated() { return status == Status.MIGRATED; }
    }

    /**
     * Accepts flat global/world profiles (including native schemas 8/7), provider overrides,
     * and explicit profile wrappers. Defaults are one full owned provider JSON declaration.
     * The caller supplies constructor-safe target validation; this class loads no registries.
     */
    public static Plan plan(JsonObject source, JsonObject ownedDefaults, Predicate<String> targetExists) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ownedDefaults, "ownedDefaults");
        Objects.requireNonNull(targetExists, "targetExists");
        JsonObject copy = source.deepCopy();
        List<String> changes = new ArrayList<>(), problems = new ArrayList<>(), warnings = new ArrayList<>();
        String owner = text(ownedDefaults.get("provider_modid"));
        if (!"basepower".equals(owner) && !"baseelectric".equals(owner)) {
            problems.add("Defaults must belong to basepower or baseelectric");
        }
        visit(copy, "$", ownedDefaults, owner, targetExists, changes, problems, warnings, 0);
        return new Plan(problems.isEmpty() ? copy : source, changes, problems, warnings);
    }

    private record Rule(String section, String id, JsonObject json, Set<String> outputs,
                        Set<String> domains, boolean legacy, boolean retained) {}

    private static void visit(JsonObject root, String path, JsonObject defaults, String owner,
            Predicate<String> exists, List<String> changes, List<String> problems, List<String> warnings, int depth) {
        if (depth > 8) { problems.add(path + ": too many profile wrappers"); return; }
        List<Rule> rules = new ArrayList<>();
        for (String section : List.of("ores", "fluid_deposits")) {
            JsonObject table = object(root, section, path, problems);
            if (table == null) continue;
            for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
                if (!entry.getValue().isJsonObject()) { problems.add(path + "/" + section + "/" + entry.getKey() + ": expected rule object"); continue; }
                JsonObject rule = entry.getValue().getAsJsonObject();
                String at = path + "/" + section + "/" + entry.getKey();
                boolean legacy = legacyId(entry.getKey()) || legacyId(text(rule.get("block")))
                        || "poweradvantage".equals(text(rule.get("source_provider")))
                        || "electricadvantage".equals(text(rule.get("source_provider")));
                JsonElement declared = defaults.get(section);
                boolean currentDefault = declared != null && declared.isJsonObject()
                        && declared.getAsJsonObject().has(entry.getKey());
                Set<String> outputs = outputs(rule, entry.getKey(), at, owner, exists, changes, problems);
                rules.add(new Rule(section, entry.getKey(), rule, outputs, domains(root, rule, section, outputs, at, problems),
                        legacy && !currentDefault, legacy));
            }
        }
        JsonObject oil = object(root, "oil", path, problems);
        if (oil != null) {
            boolean legacy = legacyId(text(oil.get("block")));
            Set<String> outputs = outputs(oil, "", path + "/oil", owner, exists, changes, problems);
            Set<String> domains = enabled(root, "place_crude_oil", true, path, problems)
                    && enabled(root, "place_fluid_deposits", true, path, problems)
                    && enabled(oil, "enabled", true, path + "/oil", problems) && positive(oil, path + "/oil", problems)
                    ? Set.of("minecraft:overworld") : Set.of();
            rules.add(new Rule("oil", "oil", oil, outputs, domains, legacy, legacy));
        }
        JsonObject policies = object(root, "ore_source_policies", path, problems);
        if (policies != null) {
            for (Map.Entry<String, JsonElement> entry : policies.entrySet()) {
                if (!entry.getValue().isJsonObject()) continue;
                JsonElement candidates = entry.getValue().getAsJsonObject().get("candidates");
                if (candidates != null && candidates.isJsonArray()) {
                    int index = 0;
                    for (JsonElement candidate : candidates.getAsJsonArray()) {
                        if (candidate.isJsonObject()) rewrite(candidate.getAsJsonObject(), "registry_id",
                                path + "/ore_source_policies/" + entry.getKey() + "/candidates/" + index, owner, exists, changes, problems);
                        index++;
                    }
                }
            }
        }
        for (Rule legacy : rules) {
            if (!legacy.legacy || legacy.domains.isEmpty()) continue;
            for (Rule current : rules) {
                if (current.legacy || current.domains.isEmpty() || !overlap(legacy.domains, current.domains)
                        || legacy.outputs.stream().noneMatch(current.outputs::contains)) continue;
                String comparison = legacy.section.equals(current.section) && legacy.json.equals(current.json)
                        ? "equivalent" : "conflicting";
                problems.add(path + ": ambiguous " + comparison + " active output collision: "
                        + legacy.section + "/" + legacy.id + " and " + current.section + "/" + current.id);
            }
        }
        String provider = text(root.get("provider_modid"));
        if (owner != null && (provider == null || provider.equals(owner))) {
            seed(root, path, defaults, owner, rules, exists, changes, problems);
        } else if (owner != null && provider != null && !provider.equals(owner) && !rules.isEmpty()) {
            warnings.add(path + ": provider identity retained; no foreign-namespace default guards inserted into " + provider);
        }
        for (String wrapper : WRAPPERS) {
            JsonElement nested = root.get(wrapper);
            if (nested != null && nested.isJsonObject() && profileShape(nested.getAsJsonObject())) {
                visit(nested.getAsJsonObject(), path + "/" + wrapper, defaults, owner, exists, changes, problems, warnings, depth + 1);
            }
        }
    }

    private static boolean profileShape(JsonObject value) {
        return value.has("schema_version") && (value.has("ores") || value.has("fluid_deposits") || value.has("oil"));
    }

    private static Set<String> outputs(JsonObject rule, String id, String path, String owner, Predicate<String> exists,
            List<String> changes, List<String> problems) {
        if (!rule.has("block")) {
            String mapped = mapped(id, owner);
            if (!mapped.equals(id) && validTarget(mapped, path, exists, problems)) {
                rule.addProperty("block", mapped);
                changes.add(path + "/block: implicit " + id + " -> " + mapped);
            }
        } else rewrite(rule, "block", path, owner, exists, changes, problems);
        rewrite(rule, "deep_output", path, owner, exists, changes, problems);
        Set<String> result = new LinkedHashSet<>();
        String primary = text(rule.get("block"));
        if (primary != null) result.add(primary);
        else if (!id.isEmpty()) result.add(id);
        String deep = text(rule.get("deep_output"));
        if (deep != null) result.add(deep);
        JsonElement outputs = rule.get("outputs");
        if (outputs != null) {
            if (!outputs.isJsonArray()) problems.add(path + "/outputs: expected array");
            else {
                int index = 0;
                for (JsonElement value : outputs.getAsJsonArray()) {
                    if (!value.isJsonObject()) problems.add(path + "/outputs/" + index + ": expected output object");
                    else {
                        JsonObject output = value.getAsJsonObject();
                        rewrite(output, "block", path + "/outputs/" + index, owner, exists, changes, problems);
                        String block = text(output.get("block"));
                        if (block != null) result.add(block);
                    }
                    index++;
                }
            }
        }
        return result;
    }

    private static void rewrite(JsonObject object, String key, String path, String owner, Predicate<String> exists,
            List<String> changes, List<String> problems) {
        if (!object.has(key)) return;
        String before = text(object.get(key));
        if (before == null) { problems.add(path + "/" + key + ": expected output identifier string"); return; }
        String after = mapped(before, owner);
        if (!after.equals(before) && validTarget(after, path + "/" + key, exists, problems)) {
            object.addProperty(key, after);
            changes.add(path + "/" + key + ": " + before + " -> " + after);
        }
    }

    private static String mapped(String value, String owner) {
        if ("basepower".equals(owner) && value.startsWith("poweradvantage:")) return "basepower:" + value.substring("poweradvantage:".length());
        if ("baseelectric".equals(owner) && value.startsWith("electricadvantage:")) return "baseelectric:" + value.substring("electricadvantage:".length());
        return value.equals("minecraft:quartz_ore") ? "minecraft:nether_quartz_ore" : value;
    }

    private static boolean legacyId(String value) {
        return value != null && (value.startsWith("poweradvantage:") || value.startsWith("electricadvantage:")
                || value.startsWith("basepower:legacy/") || value.startsWith("baseelectric:legacy/")
                || value.equals("minecraft:quartz_ore") || value.equals("orespawn:legacy/orespawn/quartz_ore"));
    }

    private static Set<String> domains(JsonObject root, JsonObject rule, String section, Set<String> outputs,
            String path, List<String> problems) {
        if (!enabled(rule, "enabled", true, path, problems)) return Set.of();
        if (section.equals("fluid_deposits") && !enabled(root, "place_fluid_deposits", true, path, problems)) return Set.of();
        if (section.equals("ores") && (outputs.contains("minecraft:nether_quartz_ore")
                || enabled(rule, "native_generation", false, path, problems))
                && !enabled(root, "manage_vanilla_ores", false, path, problems)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String key : List.of("dimensions", "dimension_selectors")) {
            JsonObject table = object(rule, key, path, problems);
            if (table == null) continue;
            for (Map.Entry<String, JsonElement> entry : table.entrySet()) {
                if (!entry.getValue().isJsonObject()) { problems.add(path + "/" + key + ": expected placement object"); continue; }
                JsonObject placement = entry.getValue().getAsJsonObject();
                if (enabled(placement, "enabled", true, path, problems) && positive(placement, path, problems)) result.add(entry.getKey());
            }
        }
        return result;
    }

    private static boolean positive(JsonObject rule, String path, List<String> problems) {
        if (!rule.has("frequency")) return true;
        JsonElement value = rule.get("frequency");
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            problems.add(path + "/frequency: expected number"); return false;
        }
        return value.getAsBigDecimal().signum() > 0;
    }

    private static boolean enabled(JsonObject object, String key, boolean fallback, String path, List<String> problems) {
        if (!object.has(key)) return fallback;
        JsonElement value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            problems.add(path + "/" + key + ": expected boolean"); return false;
        }
        return value.getAsBoolean();
    }

    private static boolean overlap(Set<String> first, Set<String> second) {
        for (String a : first) for (String b : second) {
            if (a.equals(b) || a.equals(ORDINARY) && ordinary(b) || b.equals(ORDINARY) && ordinary(a)) return true;
        }
        return false;
    }

    private static boolean ordinary(String dimension) {
        return !dimension.equals("minecraft:the_nether") && !dimension.equals("minecraft:the_end");
    }

    private static void seed(JsonObject root, String path, JsonObject defaults, String owner, List<Rule> rules,
            Predicate<String> exists, List<String> changes, List<String> problems) {
        Set<String> retained = new LinkedHashSet<>();
        for (Rule rule : rules) if (rule.retained) {
            for (String output : rule.outputs) if (output.startsWith(owner + ":")) retained.add(output);
        }
        if (retained.isEmpty()) return;
        if (!root.has("provider_modid") && defaults.has("profile_defaults")
                && defaults.get("profile_defaults").isJsonObject() && !defaults.getAsJsonObject("profile_defaults").isEmpty()) {
            JsonObject manifests = object(root, "providers", path, problems);
            if (manifests == null && !root.has("providers")) {
                manifests = new JsonObject(); root.add("providers", manifests);
            }
            if (manifests != null) {
                JsonObject manifest = object(manifests, owner, path + "/providers", problems);
                if (manifest == null && !manifests.has(owner)) {
                    manifest = new JsonObject(); manifests.add(owner, manifest);
                }
                if (manifest != null && !manifest.has("profile_defaults_applied")) {
                    manifest.addProperty("profile_defaults_applied", true);
                    changes.add(path + "/providers/" + owner + "/profile_defaults_applied: retained legacy root choices are authoritative");
                } else if (manifest != null && !enabled(manifest, "profile_defaults_applied", false, path, problems)) {
                    for (Map.Entry<String, JsonElement> entry : defaults.getAsJsonObject("profile_defaults").entrySet()) {
                        if (root.has(entry.getKey()) && !root.get(entry.getKey()).equals(entry.getValue())) {
                            problems.add(path + ": explicit provider default overlay conflicts with saved " + entry.getKey());
                        }
                    }
                }
            }
        }
        for (String section : List.of("ores", "fluid_deposits")) {
            JsonObject source = object(defaults, section, "defaults", problems);
            if (source == null) continue;
            JsonObject destination = object(root, section, path, problems);
            for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
                if (destination != null && destination.has(entry.getKey())) continue;
                if (!entry.getKey().startsWith(owner + ":") || !entry.getValue().isJsonObject()) {
                    problems.add("Unowned/malformed default rule: " + entry.getKey()); continue;
                }
                JsonObject rule = entry.getValue().getAsJsonObject();
                String block = text(rule.get("block"));
                if (block == null || !retained.contains(block)) continue;
                if (!validTarget(block, "defaults/" + entry.getKey(), exists, problems)) continue;
                if (destination == null) { destination = new JsonObject(); root.add(section, destination); }
                JsonObject guard = rule.deepCopy();
                guard.addProperty("enabled", false);
                guard.addProperty("source_provider", owner);
                destination.add(entry.getKey(), guard);
                changes.add(path + "/" + section + "/" + entry.getKey() + ": seeded disabled owned default; legacy placement retained");
                if (owner.equals(text(root.get("provider_modid")))
                        && new JsonPrimitive(4).equals(root.get("schema_version"))
                        && section.equals("ores") && requiresProviderSchemaFive(guard)) {
                    root.addProperty("schema_version", 5);
                    changes.add(path + "/schema_version: 4 -> 5; seeded ore fields require provider schema 5");
                }
            }
        }
    }

    private static boolean requiresProviderSchemaFive(JsonObject ore) {
        if (ore.has("material")) return true;
        for (String section : List.of("dimensions", "dimension_selectors")) {
            JsonElement placements = ore.get(section);
            if (placements == null || !placements.isJsonObject()) continue;
            for (JsonElement placement : placements.getAsJsonObject().asMap().values()) {
                if (placement.isJsonObject() && placement.getAsJsonObject().has("placement_channel")) return true;
            }
        }
        return false;
    }

    private static boolean validTarget(String value, String path, Predicate<String> exists, List<String> problems) {
        boolean valid = value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") && !value.equals("minecraft:air");
        try { valid = valid && exists.test(value); }
        catch (RuntimeException error) { valid = false; }
        if (!valid) problems.add(path + ": invalid/unavailable target " + value);
        return valid;
    }

    private static JsonObject object(JsonObject root, String key, String path, List<String> problems) {
        if (!root.has(key)) return null;
        if (root.get(key).isJsonObject()) return root.getAsJsonObject(key);
        problems.add(path + "/" + key + ": expected object");
        return null;
    }

    private static String text(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : null;
    }

    /**
     * Migrates one caller-selected file. Atomic moves are mandatory; unsupported filesystems
     * fail rather than falling back to non-atomic replacement. Content-addressed backups and
     * reports never replace different pre-existing evidence. PREPARED is not a success receipt.
     */
    public static synchronized Result migrate(Path file, JsonObject ownedDefaults, Predicate<String> targetExists) throws IOException {
        Path path = file.toAbsolutePath().normalize();
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Result(Status.MISSING, null, null, null);
        requireRegular(path);
        if (Files.size(path) > MAX_BYTES) throw new IOException("Oversized OreSpawn profile: " + path);
        byte[] before = Files.readAllBytes(path);
        if (before.length > MAX_BYTES) throw new IOException("Oversized OreSpawn profile: " + path);
        String beforeHash = hash(before);
        Plan plan;
        try { plan = plan(parse(before), ownedDefaults, targetExists); }
        catch (RuntimeException error) { throw new IOException("Invalid OreSpawn profile: " + path, error); }
        if (!plan.isRefused() && !plan.isChanged()) return new Result(Status.UNCHANGED, null, null, plan);
        Path backup = path.resolveSibling(path.getFileName() + ".advantage-output-" + beforeHash + ".bak");
        byte[] after = bytes(plan.getJson());
        JsonObject receipt = new JsonObject();
        receipt.addProperty("format", FORMAT);
        receipt.addProperty("file", path.toString());
        receipt.addProperty("source_sha256", beforeHash);
        receipt.addProperty("result_sha256", plan.isRefused() ? beforeHash : hash(after));
        receipt.addProperty("backup", plan.isRefused() ? null : backup.toString());
        receipt.add("changes", GSON.toJsonTree(plan.getChanges()));
        receipt.add("problems", GSON.toJsonTree(plan.getProblems()));
        receipt.add("warnings", GSON.toJsonTree(plan.getWarnings()));
        receipt.addProperty("status", plan.isRefused() ? "REFUSED" : "PREPARED");
        Path report = path.resolveSibling(path.getFileName() + ".advantage-output-" + beforeHash
                + "-" + hash(bytes(receipt)).substring(0, 16) + ".json");
        if (Files.exists(report, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(report);
            JsonObject previous = parse(Files.readAllBytes(report));
            JsonObject previousBody = previous.deepCopy(), currentBody = receipt.deepCopy();
            String previousStatus = text(previousBody.remove("status"));
            currentBody.remove("status");
            if (previousStatus == null || !List.of("PREPARED", "MIGRATED", "REFUSED").contains(previousStatus)
                    || !previousBody.equals(currentBody)) {
                throw new IOException("Conflicting migration report: " + report);
            }
        }
        if (plan.isRefused()) {
            if (!Files.exists(report)) atomicWrite(report, bytes(receipt));
            return new Result(Status.REFUSED, null, report, plan);
        }
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            requireRegular(backup);
            if (!Arrays.equals(Files.readAllBytes(backup), before)) throw new IOException("Conflicting migration backup: " + backup);
        } else atomicWrite(backup, before);
        atomicWrite(report, bytes(receipt));
        requireRegular(path);
        if (!Arrays.equals(before, Files.readAllBytes(path))) throw new IOException("Profile changed during migration: " + path);
        atomicWrite(path, after);
        receipt.addProperty("status", "MIGRATED");
        atomicWrite(report, bytes(receipt));
        return new Result(Status.MIGRATED, backup, report, plan);
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path)) {
            throw new IOException("Aliased/non-regular migration file: " + path);
        }
        for (Path part = path; part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("Aliased migration path: " + path);
        }
    }

    private static void atomicWrite(Path path, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), ".advantage-output-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(content);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] bytes(JsonObject object) { return (GSON.toJson(object) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8); }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static JsonObject parse(byte[] bytes) throws IOException {
        String utf8 = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        try (JsonReader reader = new JsonReader(new StringReader(utf8))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement value = read(reader, 0);
            if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Expected one profile object");
            return value.getAsJsonObject();
        }
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("Profile nesting limit exceeded");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name)) throw new IOException("Duplicate profile key: " + name);
                    object.add(name, read(reader, depth + 1));
                }
                reader.endObject(); return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1));
                reader.endArray(); return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Invalid profile token: " + reader.peek());
        }
    }
}
