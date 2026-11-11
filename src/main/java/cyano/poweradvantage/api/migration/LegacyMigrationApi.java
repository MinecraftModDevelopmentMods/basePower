package cyano.poweradvantage.api.migration;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;

/**
 * Registration surface for Advantage add-ons that need pre-flattening world
 * migration. PowerAdvantage owns the hook; add-ons only describe their legacy
 * identities and metadata codecs.
 */
public final class LegacyMigrationApi {
    @FunctionalInterface
    public interface BlockStateCodec {
        BlockState decode(Block currentBlock, int legacyMetadata);
    }

    @FunctionalInterface
    public interface NbtMigration {
        boolean migrate(CompoundTag compound);
    }

    private static final Set<String> NAMESPACES = new LinkedHashSet<>();
    private static final Map<String, String> NAMESPACE_ALIASES = new LinkedHashMap<>();
    private static final Map<Identifier, Identifier> BLOCK_ALIASES = new LinkedHashMap<>();
    private static final Map<Identifier, Identifier> ITEM_ALIASES = new LinkedHashMap<>();
    private static final Map<Identifier, BlockStateCodec> BLOCK_CODECS = new LinkedHashMap<>();
    private static final Map<String, String> TILE_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String> ENTITY_ALIASES = new LinkedHashMap<>();
    private static final Map<String, String> FLUID_ALIASES = new LinkedHashMap<>();
    private static final Map<String, Identifier> FLUID_BUCKETS = new LinkedHashMap<>();
    private static final Set<Identifier> DURABLE_ITEMS = new LinkedHashSet<>();
    private static final Map<String, NbtMigration> NBT_MIGRATIONS = new LinkedHashMap<>();

    private LegacyMigrationApi() {}

    public static synchronized void registerNamespace(String namespace) {
        NAMESPACES.add(normalize(namespace));
    }

    /** Only installed mods contribute aliases. Explicit path aliases still take precedence. */
    public static synchronized void registerNamespaceAlias(String legacyNamespace, String currentNamespace) {
        String legacy = normalize(legacyNamespace);
        String current = normalize(currentNamespace);
        registerNamespace(legacy);
        registerNamespace(current);
        NAMESPACE_ALIASES.put(legacy, current);
    }

    public static synchronized Identifier registryTarget(Identifier id) {
        String current = NAMESPACE_ALIASES.get(id.getNamespace());
        return current == null ? id : Identifier.fromNamespaceAndPath(current, id.getPath());
    }

    public static synchronized void registerBlockAlias(Identifier legacyId, Identifier currentId) {
        BLOCK_ALIASES.put(legacyId, currentId);
        registerNamespace(legacyId.getNamespace());
    }

    public static synchronized void registerItemAlias(Identifier legacyId, Identifier currentId) {
        ITEM_ALIASES.put(legacyId, currentId);
        registerNamespace(legacyId.getNamespace());
    }

    public static synchronized void registerBlockStateCodec(Identifier legacyId, BlockStateCodec codec) {
        if (codec == null) throw new NullPointerException("codec");
        BLOCK_CODECS.put(legacyId, codec);
        registerNamespace(legacyId.getNamespace());
    }

    public static synchronized void registerTileEntityAlias(String legacyId, String currentId) {
        TILE_ALIASES.put(normalizeIdentity(legacyId), normalizeIdentity(currentId));
    }

    public static synchronized void registerTileEntityAliases(Identifier current) {
        String path = current.getPath();
        registerTileVariants(current.getNamespace(), path, current.toString());
        for (Map.Entry<String, String> alias : NAMESPACE_ALIASES.entrySet()) {
            if (alias.getValue().equals(current.getNamespace())) {
                registerTileVariants(alias.getKey(), path, current.toString());
            }
        }
    }

    private static void registerTileVariants(String namespace, String path, String target) {
        registerTileEntityAlias(namespace + ":" + path, target);
        registerTileEntityAlias(namespace + "." + path, target);
        registerTileEntityAlias("minecraft:" + namespace + "." + path, target);
    }

    public static synchronized void registerEntityAlias(String legacyId, String currentId) {
        ENTITY_ALIASES.put(normalizeIdentity(legacyId), normalizeIdentity(currentId));
    }

    public static synchronized void registerFluidAlias(String legacyName, String currentName) {
        FLUID_ALIASES.put(normalizeIdentity(legacyName), normalizeIdentity(currentName));
    }

    public static synchronized void registerLegacyFluidBucket(String legacyFluidName, Identifier bucketId) {
        FLUID_BUCKETS.put(normalizeIdentity(legacyFluidName), bucketId);
    }

    public static synchronized void registerDurableItem(Identifier itemId) {
        DURABLE_ITEMS.add(itemId);
    }

    public static synchronized void registerNbtMigration(String id, NbtMigration migration) {
        if (migration == null) throw new NullPointerException("migration");
        NBT_MIGRATIONS.put(normalizeIdentity(id), migration);
    }

    public static synchronized boolean handlesNamespace(String namespace) {
        return NAMESPACES.contains(normalize(namespace));
    }

    public static synchronized Identifier blockTarget(Identifier legacyId) {
        Identifier target = BLOCK_ALIASES.get(legacyId);
        return target == null ? registryTarget(legacyId) : target;
    }

    public static synchronized Identifier itemTarget(Identifier legacyId) {
        Identifier target = ITEM_ALIASES.get(legacyId);
        return target == null ? registryTarget(legacyId) : target;
    }

    public static synchronized BlockState decodeBlockState(Identifier legacyId, Block block, int metadata) {
        BlockStateCodec codec = BLOCK_CODECS.get(legacyId);
        if (codec == null) codec = BLOCK_CODECS.get(blockTarget(legacyId));
        return codec == null ? block.defaultBlockState() : codec.decode(block, metadata & 15);
    }

    public static synchronized String tileTarget(String legacyId) {
        return alias(TILE_ALIASES, legacyId);
    }

    public static synchronized String entityTarget(String legacyId) {
        return alias(ENTITY_ALIASES, legacyId);
    }

    public static synchronized String fluidTarget(String legacyName) {
        return alias(FLUID_ALIASES, legacyName);
    }

    public static synchronized Identifier bucketTarget(String legacyFluidName) {
        String normalized = normalizeIdentity(legacyFluidName);
        Identifier direct = FLUID_BUCKETS.get(normalized);
        if (direct != null) return direct;
        String fluidTarget = alias(FLUID_ALIASES, normalized);
        return FLUID_BUCKETS.get(fluidTarget);
    }

    public static synchronized boolean isDurableItem(Identifier id) {
        return DURABLE_ITEMS.contains(id) || DURABLE_ITEMS.contains(itemTarget(id));
    }

    public static synchronized boolean migrateRegisteredNbt(CompoundTag compound) {
        boolean changed = false;
        for (NbtMigration migration : NBT_MIGRATIONS.values()) changed |= migration.migrate(compound);
        return changed;
    }

    public static synchronized Set<String> namespaces() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(NAMESPACES));
    }

    public static synchronized Map<Identifier, BlockStateCodec> blockStateCodecs() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(BLOCK_CODECS));
    }

    private static String alias(Map<String, String> aliases, String value) {
        String normalized = normalizeLookup(value);
        if (normalized.isEmpty()) return normalized;
        String target = aliases.get(normalized);
        return target == null ? normalized : target;
    }

    private static String normalize(String value) {
        if (value == null) throw new NullPointerException("value");
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) throw new IllegalArgumentException("Identity cannot be empty");
        return normalized;
    }

    private static String normalizeIdentity(String value) {
        return normalize(value);
    }

    private static String normalizeLookup(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
