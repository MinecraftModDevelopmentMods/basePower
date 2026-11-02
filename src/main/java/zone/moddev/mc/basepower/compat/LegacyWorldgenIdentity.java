package zone.moddev.mc.basepower.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded identity migration for released Power/Electric 1.10 configurations.
 *
 * <p>Call {@link #migrateConfig} during the owning mod's preInit, before
 * OreSpawn 4.0.8's init imports OS1/OS3 files and discovers providers. Provider
 * IDs MUST become the loaded mod ID: the pinned engine rejects unloaded owners
 * and rules outside their owner's namespace. An old OS1 filename must not
 * remain discoverable, or the bridge imports it as a standalone OreSpawn rule.
 *
 * <p>Call {@link #migrateWorld} only before OreSpawn reads the selected world's
 * profile. With required-after:orespawn, an ordinary serverAboutToStart callback
 * is too late. Native required-before:orespawn ordering on Power permits Power
 * to migrate both loaded identities before OreSpawn's callback, without ASM.
 * This helper neither reloads OreSpawn nor discovers/scans worlds. Exceptions
 * must abort startup, never fall through to fresh defaults. Validation precedes
 * all changes; filesystem failures can leave a partial, backed-up batch that is
 * safe to retry, but must not be used to continue the same startup.
 */
public final class LegacyWorldgenIdentity {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
            .disableHtmlEscaping().serializeNulls().create();
    private static final LinkOption[] NO_LINKS = { LinkOption.NOFOLLOW_LINKS };
    // OreSpawn 4.1 uses distinct global/world schemas; provider files keep their own version.
    private static final int MAX_GLOBAL_PROFILE_SCHEMA = 8;
    private static final int MAX_WORLD_PROFILE_SCHEMA = 7;
    private static final List<String> RULE_MAPS = Arrays.asList("ores", "fluid_deposits", "rocks",
            "geomes", "templates", "biome_palettes", "dimension_materials");
    private static final List<String> BLOCK_FIELDS = Arrays.asList("block", "blockID", "top_block",
            "filler_block", "underwater_block", "ceiling_block", "snow_block", "ice_block");
    private static final List<String> BLOCK_LISTS = Arrays.asList("blocks", "host_blocks", "outputs");
    private static final List<String> OWNER_FIELDS = Arrays.asList("provider_modid", "source_mod",
            "source_provider", "sulfur_source", "lithium_source", "crude_oil_source",
            "sulfur_provider", "lithium_provider", "crude_oil_provider");

    private LegacyWorldgenIdentity() { }

    /** Fixed config filenames only. The return value counts writes and retirements. */
    public static synchronized int migrateConfig(Path configDirectory, String oldModId,
            String newModId) throws IOException {
        Pair pair = new Pair(oldModId, newModId);
        Batch batch = new Batch(configDirectory, pair);
        Document oldProvider = batch.read(oldModId + "-orespawn.json", "provider");
        Document newProvider = batch.read(newModId + "-orespawn.json", "provider");
        Document profile = batch.read("orespawn-worldgen.json", "global-profile");
        List<Document> legacy = new ArrayList<>();
        for (String directory : Arrays.asList("orespawn", "orespawn3")) {
            String kind = "orespawn".equals(directory) ? "os1" : "os3";
            Document old = batch.read(directory + "/" + oldModId + ".json", kind);
            Document modern = batch.read(directory + "/" + newModId + ".json", kind);
            if (old != null) legacy.add(old);
            if (modern != null) legacy.add(modern);
            if (old != null && modern != null && !old.migrated.equals(modern.migrated)) {
                throw problem(old.path, "different old/new legacy authorities; resolve explicitly");
            }
        }
        if (oldProvider != null && newProvider != null
                && !oldProvider.migrated.equals(newProvider.migrated)) {
            throw problem(oldProvider.path, "different old/new provider authorities; resolve explicitly");
        }
        // Already-imported standalone rules cannot be claimed by another provider.
        Document standalone = batch.read("orespawn-orespawn.json", "standalone");
        if (standalone != null && containsStandalone(standalone.original, pair)) {
            throw problem(standalone.path, "owned standalone legacy rules require explicit reconciliation");
        }
        Document provider = newProvider != null ? newProvider : oldProvider;
        if (provider != null) {
            batch.write(newModId + "-orespawn.json", provider.migrated);
            if (oldProvider != null) batch.retire(oldProvider);
            // The pinned bridge overwrites explicit provider files, so none of
            // these owned legacy inputs may remain in its *.json discovery.
            for (Document source : legacy) batch.retire(source);
            if (profile != null) checkProviderOverlap(profile.migrated, provider.migrated, pair);
        } else if (!legacy.isEmpty()) {
            if (batch.exists(".orespawn-legacy-bridge-migrated")) {
                throw problem(batch.root, "bridge marker exists but owned provider is absent; legacy import would be skipped");
            }
            String kind = legacy.get(0).kind;
            for (Document source : legacy) {
                if (!kind.equals(source.kind)) {
                    throw problem(source.path, "both OS1 and OS3 authorities exist without an explicit provider");
                }
            }
            if (profile != null && hasOwnedRules(profile.migrated, pair)) {
                throw problem(profile.path, "saved owned rules plus pending legacy import require explicit reconciliation");
            }
            String directory = "os1".equals(kind) ? "orespawn" : "orespawn3";
            batch.write(directory + "/" + newModId + ".json", legacy.get(0).migrated);
            for (Document source : legacy) {
                if (source.path.getFileName().toString().equals(oldModId + ".json")) batch.retire(source);
            }
        }
        if (profile != null) batch.write("orespawn-worldgen.json", profile.migrated);
        return batch.commit();
    }

    /** Only serverconfig/orespawn-worldgen.json in the explicitly supplied root. */
    public static synchronized int migrateWorld(Path worldRoot, String oldModId,
            String newModId) throws IOException {
        Pair pair = new Pair(oldModId, newModId);
        Batch batch = new Batch(worldRoot, pair);
        Document profile = batch.read("serverconfig/orespawn-worldgen.json", "world-profile");
        if (profile != null) batch.write("serverconfig/orespawn-worldgen.json", profile.migrated);
        return batch.commit();
    }

    /** Exact selector values only; useful to the owning Forge configuration code. */
    public static String migrateSelectorValue(String value, String oldModId, String newModId) {
        Pair pair = new Pair(oldModId, newModId);
        return pair.oldId.equals(value) ? pair.newId : value;
    }

    private static final class Pair {
        final String oldId;
        final String newId;
        Pair(String oldId, String newId) {
            if (!("poweradvantage".equals(oldId) && "basepower".equals(newId))
                    && !("electricadvantage".equals(oldId) && "baseelectric".equals(newId))) {
                throw new IllegalArgumentException("Unsupported worldgen identity pair");
            }
            this.oldId = oldId;
            this.newId = newId;
        }
        String id(String text) throws IOException {
            if (!text.startsWith(oldId + ":")) return text;
            if (!text.substring(oldId.length() + 1).matches("[a-z0-9_./-]+")) {
                throw new IOException("Invalid owned identifier: " + text);
            }
            return newId + text.substring(oldId.length());
        }
        String rule(String text) throws IOException {
            String result = id(text);
            // OS1 generates names from output namespace + path + ordinal.
            String prefix = newId + ":legacy/" + oldId + "_";
            return result.startsWith(prefix) ? newId + ":legacy/" + newId + "_"
                    + result.substring(prefix.length()) : result;
        }
    }

    private static JsonElement rewrite(JsonElement input, Pair pair, String context) throws IOException {
        if (input.isJsonArray()) {
            JsonArray output = new JsonArray();
            for (JsonElement item : input.getAsJsonArray()) {
                if (isString(item) && (BLOCK_LISTS.contains(context) || context.startsWith("known_"))) {
                    output.add(new JsonPrimitive(context.startsWith("known_")
                            ? pair.rule(item.getAsString()) : pair.id(item.getAsString())));
                } else output.add(rewrite(item, pair, context));
            }
            return output;
        }
        if (!input.isJsonObject()) return input;
        JsonObject output = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : input.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            String target = RULE_MAPS.contains(context) ? pair.rule(key)
                    : (("providers".equals(context) || "ore_providers".equals(context))
                    && pair.oldId.equals(key) ? pair.newId : key);
            JsonElement mapped;
            if (BLOCK_FIELDS.contains(key) || ("name".equals(key) && "blocks".equals(context))) {
                if (!isString(value)) throw new IOException("Block reference must be a string: " + key);
                mapped = new JsonPrimitive(pair.id(value.getAsString()));
            } else if (OWNER_FIELDS.contains(key) || ("resource_sources".equals(context)
                    && Arrays.asList("sulfur", "lithium", "crude_oil").contains(key))) {
                if (!isString(value)) throw new IOException("Owner selector must be a string: " + key);
                mapped = new JsonPrimitive(pair.oldId.equals(value.getAsString()) ? pair.newId : value.getAsString());
            } else if (Arrays.asList("notes", "description", "comment", "metadata", "host_tags", "tags").contains(key)) {
                mapped = value;
            } else mapped = rewrite(value, pair, key);
            if (output.has(target) && !output.get(target).equals(mapped)) {
                throw new IOException("Conflicting identity entries: " + key + " -> " + target);
            }
            output.add(target, mapped);
        }
        return output;
    }

    private static boolean isString(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static void validate(JsonObject root, String kind, Pair pair) throws IOException {
        if ("os1".equals(kind)) {
            if (!root.has("dimensions") || !root.get("dimensions").isJsonArray()) {
                throw new IOException("OS1 dimensions must be an array");
            }
            for (JsonElement element : root.getAsJsonArray("dimensions")) {
                if (!element.isJsonObject()) throw new IOException("Invalid OS1 dimension");
                JsonObject dimension = element.getAsJsonObject();
                JsonElement id = dimension.get("dimension");
                if (id == null || !(isString(id) && "+".equals(id.getAsString()))) {
                    integer(id, "dimension");
                }
                if (!dimension.has("ores") || !dimension.get("ores").isJsonArray()) {
                    throw new IOException("OS1 ores must be an array");
                }
                for (JsonElement ore : dimension.getAsJsonArray("ores")) {
                    if (!ore.isJsonObject() || !isString(ore.getAsJsonObject().get("blockID"))) {
                        throw new IOException("OS1 ore must have a blockID string");
                    }
                    JsonObject declaration = ore.getAsJsonObject();
                    for (String field : Arrays.asList("size", "variation", "minHeight", "maxHeight", "blockMeta")) {
                        if (!declaration.has(field)) continue;
                        int number = integer(declaration.get(field), field);
                        int minimum = "size".equals(field) || "maxHeight".equals(field) ? 1 : 0;
                        int maximum = "blockMeta".equals(field) ? 15 : "minHeight".equals(field) ? 255
                                : "maxHeight".equals(field) ? 256 : Integer.MAX_VALUE;
                        if (number < minimum || number > maximum) throw new IOException("Invalid OS1 " + field);
                    }
                    if (declaration.has("minHeight") && declaration.has("maxHeight")
                            && integer(declaration.get("minHeight"), "minHeight")
                            >= integer(declaration.get("maxHeight"), "maxHeight")) {
                        throw new IOException("Invalid OS1 exclusive height interval");
                    }
                    if (declaration.has("frequency")) {
                        JsonElement frequency = declaration.get("frequency");
                        if (!frequency.isJsonPrimitive() || !frequency.getAsJsonPrimitive().isNumber()
                                || !Double.isFinite(frequency.getAsDouble()) || frequency.getAsDouble() < 0) {
                            throw new IOException("Invalid OS1 frequency");
                        }
                    }
                    if (declaration.has("biomes") && !declaration.get("biomes").isJsonArray()) {
                        throw new IOException("Invalid OS1 biome list");
                    }
                    // The pin unconditionally enables OS1 declarations. Do not
                    // pretend an unsupported user disable will survive import.
                    if (declaration.has("enabled") && (!declaration.get("enabled").isJsonPrimitive()
                            || !declaration.get("enabled").getAsJsonPrimitive().isBoolean()
                            || !declaration.get("enabled").getAsBoolean())) {
                        throw new IOException("OS1 enabled override requires an explicit provider");
                    }
                }
            }
        } else if ("os3".equals(kind)) {
            if (!root.has("spawns") || !root.get("spawns").isJsonObject()) {
                throw new IOException("OS3 spawns must be an object");
            }
            for (Map.Entry<String, JsonElement> spawn : root.getAsJsonObject("spawns").entrySet()) {
                if (!spawn.getValue().isJsonObject()) throw new IOException("Invalid OS3 spawn");
                JsonElement blocks = spawn.getValue().getAsJsonObject().get("blocks");
                if (blocks == null || !blocks.isJsonArray() || blocks.getAsJsonArray().size() == 0) {
                    throw new IOException("OS3 spawn requires output blocks");
                }
                for (JsonElement output : blocks.getAsJsonArray()) {
                    if (!output.isJsonObject() || !isString(output.getAsJsonObject().get("name"))) {
                        throw new IOException("Invalid OS3 output block");
                    }
                }
            }
        } else if ("provider".equals(kind)) {
            int schema = integer(root.get("schema_version"), "schema_version");
            if (schema < 1 || schema > 4 || integer(root.get("provider_revision"), "provider_revision") < 1
                    || !isString(root.get("provider_modid"))
                    || !pair.newId.equals(root.get("provider_modid").getAsString())) {
                throw new IOException("Invalid provider header");
            }
            int entries = 0;
            for (String section : RULE_MAPS) {
                if (!root.has(section)) continue;
                if (!root.get(section).isJsonObject()) throw new IOException("Invalid provider section: " + section);
                if ((schema == 1 && !"ores".equals(section))
                        || (schema < 3 && "fluid_deposits".equals(section))
                        || (schema < 4 && ("biome_palettes".equals(section) || "dimension_materials".equals(section)))) {
                    throw new IOException("Section not supported by provider schema: " + section);
                }
                for (Map.Entry<String, JsonElement> rule : root.getAsJsonObject(section).entrySet()) {
                    if (!rule.getKey().startsWith(pair.newId + ":") || !rule.getValue().isJsonObject()) {
                        throw new IOException("Provider rule outside owned namespace: " + rule.getKey());
                    }
                    entries++;
                }
            }
            if (entries == 0) throw new IOException("Owned provider declares no rules");
        } else if (root.has("schema_version")) {
            int schema = integer(root.get("schema_version"), "schema_version");
            int maximum = "global-profile".equals(kind) ? MAX_GLOBAL_PROFILE_SCHEMA
                    : "world-profile".equals(kind) ? MAX_WORLD_PROFILE_SCHEMA : 6;
            if (schema < 1 || schema > maximum) {
                throw new IOException("Unsupported worldgen profile schema " + schema + " for " + kind);
            }
        }
        for (String section : Arrays.asList("ores", "fluid_deposits", "providers", "ore_providers")) {
            if (root.has(section) && !root.get(section).isJsonObject()) {
                throw new IOException("Invalid worldgen section: " + section);
            }
        }
    }

    private static int integer(JsonElement value, String field) throws IOException {
        try {
            if (value == null || !value.isJsonPrimitive() || !(value.getAsJsonPrimitive().isNumber()
                    || "dimension".equals(field) && value.getAsJsonPrimitive().isString())) {
                throw new NumberFormatException();
            }
            return new BigDecimal(value.getAsString()).intValueExact();
        } catch (RuntimeException error) { throw new IOException("Invalid integer: " + field, error); }
    }

    private static boolean containsStandalone(JsonElement value, Pair pair) {
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                if (entry.getKey().startsWith("orespawn:legacy/" + pair.oldId + "/")
                        || entry.getKey().startsWith("orespawn:legacy/" + pair.newId + "/")
                        || containsStandalone(entry.getValue(), pair)) return true;
            }
        } else if (value.isJsonArray()) {
            for (JsonElement item : value.getAsJsonArray()) if (containsStandalone(item, pair)) return true;
        }
        return false;
    }

    private static boolean hasOwnedRules(JsonObject root, Pair pair) throws IOException {
        if (containsStandalone(root, pair)) throw new IOException("Standalone owned legacy rules require reconciliation");
        for (String section : Arrays.asList("ores", "fluid_deposits")) {
            if (!root.has(section)) continue;
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject(section).entrySet()) {
                if (entry.getKey().startsWith(pair.newId + ":")) return true;
            }
        }
        return false;
    }

    private static void checkProviderOverlap(JsonObject profile, JsonObject provider, Pair pair) throws IOException {
        for (String section : Arrays.asList("ores", "fluid_deposits")) {
            if (!provider.has(section)) continue;
            for (Map.Entry<String, JsonElement> supplied : provider.getAsJsonObject(section).entrySet()) {
                String block = block(supplied.getValue());
                if (block == null || !block.startsWith(pair.newId + ":")) continue;
                for (String savedSection : Arrays.asList("ores", "fluid_deposits")) {
                    if (!profile.has(savedSection)) continue;
                    for (Map.Entry<String, JsonElement> saved : profile.getAsJsonObject(savedSection).entrySet()) {
                        if (block.equals(block(saved.getValue())) && !saved.getKey().equals(supplied.getKey())
                                && (saved.getKey().contains(":legacy/") || supplied.getKey().contains(":legacy/"))) {
                            throw new IOException("Saved legacy/modern rule overlap for " + block + "; resolve explicitly");
                        }
                    }
                }
            }
        }
    }

    private static String block(JsonElement rule) {
        return rule.isJsonObject() && isString(rule.getAsJsonObject().get("block"))
                ? rule.getAsJsonObject().get("block").getAsString() : null;
    }

    private static void preserveLegacyAuthority(JsonObject profile, Pair pair) throws IOException {
        if (containsStandalone(profile, pair)) throw new IOException("Standalone owned legacy rules require reconciliation");
        boolean legacy = false;
        boolean modern = false;
        for (String section : Arrays.asList("ores", "fluid_deposits")) {
            if (!profile.has(section)) continue;
            for (Map.Entry<String, JsonElement> rule : profile.getAsJsonObject(section).entrySet()) {
                if (!rule.getKey().startsWith(pair.newId + ":")) continue;
                if (!rule.getValue().isJsonObject()) throw new IOException("Invalid owned saved rule");
                if (rule.getKey().startsWith(pair.newId + ":legacy/")) legacy = true;
                else modern = true;
            }
        }
        if (legacy && modern) throw new IOException("Saved legacy and modern owned rules require explicit reconciliation");
        if (!legacy) return;
        // Prevent an API-default rule being added beside retained OS1/OS3 rules.
        JsonObject providers = object(profile, "providers");
        JsonObject manifest = object(providers, pair.newId);
        manifest.addProperty("profile_defaults_applied", true);
        if ("basepower".equals(pair.newId)) {
            remember(manifest, "known_fluid_deposits", "basepower:fluid_deposit/desert_crude_oil");
        } else {
            remember(manifest, "known_ores", "baseelectric:ore/lithium");
            remember(manifest, "known_ores", "baseelectric:ore/sulfur");
        }
    }

    private static JsonObject object(JsonObject parent, String key) throws IOException {
        if (!parent.has(key)) parent.add(key, new JsonObject());
        if (!parent.get(key).isJsonObject()) throw new IOException("Expected object: " + key);
        return parent.getAsJsonObject(key);
    }

    private static void remember(JsonObject parent, String key, String value) throws IOException {
        if (!parent.has(key)) parent.add(key, new JsonArray());
        if (!parent.get(key).isJsonArray()) throw new IOException("Expected array: " + key);
        JsonArray array = parent.getAsJsonArray(key);
        for (JsonElement item : array) {
            if (!isString(item)) throw new IOException("Invalid known rule ID");
            if (value.equals(item.getAsString())) return;
        }
        array.add(new JsonPrimitive(value));
    }

    private static final class Document {
        final Path path;
        final String kind;
        final byte[] bytes;
        final JsonObject original;
        final JsonObject migrated;
        Document(Path path, String kind, byte[] bytes, Pair pair) throws IOException {
            this.path = path;
            this.kind = kind;
            this.bytes = bytes;
            try (JsonReader reader = new JsonReader(new StringReader(StandardCharsets.UTF_8.newDecoder()
                    .decode(ByteBuffer.wrap(bytes)).toString()))) {
                reader.setLenient(false);
                JsonElement parsed = parse(reader, 0);
                if (!parsed.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
                    throw problem(path, "expected one JSON object");
                }
                original = parsed.getAsJsonObject();
                if ("provider".equals(kind)) {
                    String expected = path.getFileName().toString().replace("-orespawn.json", "");
                    if (!isString(original.get("provider_modid"))
                            || !expected.equals(original.get("provider_modid").getAsString())) {
                        throw problem(path, "provider_modid does not match its filename");
                    }
                }
                migrated = rewrite(original, pair, "").getAsJsonObject();
                validate(migrated, kind, pair);
                if ("global-profile".equals(kind) || "world-profile".equals(kind)) preserveLegacyAuthority(migrated, pair);
            } catch (RuntimeException error) { throw problem(path, "invalid owned JSON", error); }
        }
    }

    // JsonParser is lenient even with a strict reader in older Gson. Read tokens
    // directly so malformed JSON and duplicate authority keys cannot be lost.
    private static JsonElement parse(JsonReader reader, int depth) throws IOException {
        if (depth > 128) throw new IOException("Worldgen JSON exceeds nesting limit");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON key: " + key);
                    object.add(key, parse(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(parse(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL: reader.nextNull(); return JsonNull.INSTANCE;
            default: throw new IOException("Invalid JSON token: " + reader.peek());
        }
    }

    private static final class Batch {
        final Path root;
        final Pair pair;
        final Map<Path, Document> documents = new LinkedHashMap<>();
        final Map<Path, byte[]> writes = new LinkedHashMap<>();
        final List<Path> retirements = new ArrayList<>();
        Batch(Path root, Pair pair) throws IOException {
            this.root = root.toAbsolutePath().normalize();
            this.pair = pair;
            checkPath(this.root);
            if (Files.exists(this.root, NO_LINKS) && !Files.isDirectory(this.root, NO_LINKS)) {
                throw problem(this.root, "root is not a directory");
            }
        }
        Path path(String relative) throws IOException {
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root) || target.equals(root)) throw problem(target, "path escapes root");
            checkPath(target);
            return target;
        }
        boolean exists(String relative) throws IOException { return Files.exists(path(relative), NO_LINKS); }
        Document read(String relative, String kind) throws IOException {
            Path path = path(relative);
            if (!Files.exists(path, NO_LINKS)) return null;
            if (!Files.isRegularFile(path, NO_LINKS) || Files.size(path) > 16 * 1024 * 1024) {
                throw problem(path, "expected a regular JSON file of at most 16 MiB");
            }
            Document document = new Document(path, kind, Files.readAllBytes(path), pair);
            documents.put(path, document);
            return document;
        }
        void write(String relative, JsonObject value) throws IOException {
            Path path = path(relative);
            Document existing = documents.get(path);
            if (existing != null && existing.original.equals(value)) return;
            writes.put(path, (GSON.toJson(value) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        void retire(Document source) { retirements.add(source.path); }
        int commit() throws IOException {
            // Check every snapshot and backup before the first mutation.
            for (Document document : documents.values()) unchanged(document);
            for (Path path : writes.keySet()) {
                if (!documents.containsKey(path) && Files.exists(path, NO_LINKS)) {
                    throw problem(path, "destination appeared during migration");
                }
            }
            Map<Path, Path> backups = new LinkedHashMap<>();
            for (Document document : documents.values()) {
                if (!writes.containsKey(document.path) && !retirements.contains(document.path)) continue;
                Path backup = document.path.resolveSibling(document.path.getFileName()
                        + ".before-" + pair.newId + "-" + sha256(document.bytes) + ".bak");
                checkPath(backup);
                if (Files.exists(backup, NO_LINKS) && (!Files.isRegularFile(backup, NO_LINKS)
                        || !Arrays.equals(document.bytes, Files.readAllBytes(backup)))) {
                    throw problem(backup, "backup conflict; original backup will never be overwritten");
                }
                backups.put(document.path, backup);
            }
            for (Map.Entry<Path, Path> backup : backups.entrySet()) {
                if (!Files.exists(backup.getValue(), NO_LINKS)) {
                    unchanged(documents.get(backup.getKey()));
                    atomicWrite(backup.getValue(), documents.get(backup.getKey()).bytes, false);
                }
                if (!Arrays.equals(documents.get(backup.getKey()).bytes, Files.readAllBytes(backup.getValue()))) {
                    throw problem(backup.getValue(), "backup verification failed");
                }
            }
            for (Map.Entry<Path, byte[]> write : writes.entrySet()) {
                Document old = documents.get(write.getKey());
                if (old != null) unchanged(old);
                atomicWrite(write.getKey(), write.getValue(), old != null);
            }
            for (Path source : retirements) {
                unchanged(documents.get(source));
                // Exact owned filenames only, after byte-verified immutable backup.
                Files.delete(source);
            }
            return writes.size() + retirements.size();
        }
    }

    private static void unchanged(Document document) throws IOException {
        checkPath(document.path);
        if (!Files.isRegularFile(document.path, NO_LINKS)
                || !Arrays.equals(document.bytes, Files.readAllBytes(document.path))) {
            throw problem(document.path, "source changed during migration");
        }
    }

    private static void checkPath(Path path) throws IOException {
        for (Path current = path.toAbsolutePath().normalize(); current != null; current = current.getParent()) {
            try {
                java.nio.file.attribute.BasicFileAttributes attributes = Files.readAttributes(current,
                        java.nio.file.attribute.BasicFileAttributes.class, NO_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw problem(current, "linked/reparse paths are not permitted");
                }
            } catch (java.nio.file.NoSuchFileException absent) { /* Nonexistent leaves are allowed. */ }
        }
    }

    private static void atomicWrite(Path target, byte[] bytes, boolean replace) throws IOException {
        checkPath(target);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".identity-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            checkPath(target);
            if (!replace && Files.exists(target, NO_LINKS)) throw problem(target, "destination appeared");
            if (replace) {
                // No non-atomic fallback: unsupported filesystems must stop startup.
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // ATOMIC_MOVE may replace an existing destination even without
                // REPLACE_EXISTING. Publish new files/backups with an atomic,
                // no-clobber hard link to independent, fully flushed bytes.
                Files.createLink(target, temporary);
            }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder hash = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static IOException problem(Path path, String message) { return problem(path, message, null); }
    private static IOException problem(Path path, String message, Throwable cause) {
        return new IOException("Worldgen identity migration: " + message + " (" + path + ")", cause);
    }
}
