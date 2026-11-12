package zone.moddev.mc.basepower.migration;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Dynamic;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;

/**
 * Shared Advantage pre-flattening migration hook for Minecraft 1.10 and 1.12
 * worlds. Add-ons contribute their identities through {@link LegacyMigrationApi}.
 */
public final class LegacyWorldDataHook {
    private static final Identifier BLOCK_REGISTRY = Identifier.fromNamespaceAndPath("minecraft", "blocks");
    private static final Set<String> PREPARED_WORLDS = ConcurrentHashMap.newKeySet();
    private static final String REGISTRY_SIDECAR = "basepower-legacy-registry.dat";
    private static final BitSet LEGACY_BLOCK_IDS = new BitSet();
    private static final Map<Integer, String> LEGACY_ENCHANTMENTS = new LinkedHashMap<>();
    private static final Map<Identifier, Set<Long>> LEGACY_CHUNKS = new ConcurrentHashMap<>();
    private static final Map<Long, Integer> LEGACY_BLOCK_COUNTS = new ConcurrentHashMap<>();
    private static final Map<String, String> VANILLA_BLOCK_ENTITY_IDS = vanillaBlockEntityIds();
    private static final String PRESERVE_CHUNK_MARKER = "AdvantageLegacyPreserveChunk";
    private static boolean legacyWorldActive;

    private LegacyWorldDataHook() {}

    /** Called by the coremod before Forge reads level.dat. */
    public static synchronized void prepareLegacyWorld(File levelDat) {
        AdvantageLegacyMigrations.registerPowerAdvantage();
        legacyWorldActive = false;
        LEGACY_ENCHANTMENTS.clear();
        LEGACY_CHUNKS.clear();
        LEGACY_BLOCK_COUNTS.clear();
        try {
            OreSpawnProfileMigration.migrateWorld(levelDat.toPath().toAbsolutePath().getParent());
            prepareForgeRegistryMetadata(levelDat);
            File source = legacyRegistrySource(levelDat);
            if (source != null) {
                try (FileInputStream input = new FileInputStream(source)) {
                    CompoundTag registryArchive = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                    if (!containsLegacyRegistryEntry(registryArchive)) {
                        throw new IOException("Invalid retained Advantage registry snapshot " + source);
                    }
                    preserveLegacyRegistry(levelDat, registryArchive);
                    prepareLegacyData(levelDat.getParentFile(), registryArchive.getCompoundOrEmpty("FML"));
                }
            }
            if (levelDat.isFile()) {
                // A backup can supply registry IDs, but never replace newer world data.
                try (FileInputStream current = new FileInputStream(levelDat)) {
                    CompoundTag root = NbtIo.readCompressed(current, NbtAccounter.unlimitedHeap());
                    if (source != null || hasAdvantageNamespaceAliases(root)) migrateLoosePlayerData(levelDat, root);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare legacy Advantage data in " + levelDat, exception);
        }
    }

    private static boolean hasAdvantageNamespaceAliases(CompoundTag root) {
        CompoundTag registries = root.getCompoundOrEmpty("fml").getCompoundOrEmpty("Registries");
        for (String registry : registries.keySet()) {
            for (Tag entry : registries.getCompoundOrEmpty(registry).getListOrEmpty("ids")) {
                Identifier id = safeId(((CompoundTag) entry).getStringOr("K", ""));
                if (id != null && !LegacyMigrationApi.registryTarget(id).equals(id)) return true;
            }
        }
        return false;
    }

    static void prepareForgeRegistryMetadata(File levelDat) {
        if (!levelDat.isFile()) return;
        try {
            CompoundTag root;
            try (FileInputStream input = new FileInputStream(levelDat)) {
                root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            }
            boolean changed = removeRetiredVanillaProfessionRegistry(root);
            changed |= renameFlattenedBiomeRegistry(root);
            changed |= normalizeFlattenedRegistryNames(root);
            changed |= archiveDataDrivenEnchantmentIndex(root);
            if (changed) {
                preserveRetiredRegistryArchive(levelDat, root.getCompoundOrEmpty("basepower:legacy_registry_indexes"));
                writeWithBackup(levelDat, root);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare historical Forge registry indexes in " + levelDat, exception);
        }
    }

    static boolean normalizeFlattenedRegistryNames(CompoundTag root) {
        if (root.getCompoundOrEmpty("Data").getIntOr("DataVersion", 0) < 1519) return false;
        // Only flattened lowercase snapshots: uppercase FML supplies original numeric IDs.
        CompoundTag registries = root.getCompoundOrEmpty("fml").getCompoundOrEmpty("Registries");
        Map<String, String> aliases = Map.of(
                "minecraft:potions", Registries.MOB_EFFECT.identifier().toString(),
                "minecraft:potiontypes", Registries.POTION.identifier().toString(),
                "minecraft:soundevents", Registries.SOUND_EVENT.identifier().toString(),
                "minecraft:enchantments", Registries.ENCHANTMENT.identifier().toString());
        // Validate every pair before changing any key; never merge conflicting indexes.
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            Tag legacy = registries.get(alias.getKey());
            if (legacy == null) continue;
            if (!(legacy instanceof CompoundTag)) {
                throw new IllegalStateException("Malformed historical registry snapshot " + alias.getKey());
            }
            Tag current = registries.get(alias.getValue());
            if (current != null && !legacy.equals(current)) {
                throw new IllegalStateException("Conflicting historical registry snapshots " + alias.getKey()
                        + " and " + alias.getValue() + "; preserve the world backup");
            }
        }
        boolean changed = false;
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            Tag legacy = registries.get(alias.getKey());
            if (legacy == null) continue;
            registries.put(alias.getValue(), legacy.copy());
            registries.remove(alias.getKey());
            changed = true;
        }
        return changed;
    }

    static boolean archiveDataDrivenEnchantmentIndex(CompoundTag root) {
        // Flattened items use named enchantments. Forge 52 no longer has this
        // static registry; keep its exact index for recovery, not in GameData.
        // Uppercase pre-flattening FML remains untouched for numeric-ID migration.
        CompoundTag registries = root.getCompoundOrEmpty("fml").getCompoundOrEmpty("Registries");
        String key = "minecraft:enchantment";
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(registries, key, Tag.TAG_COMPOUND)) return false;
        CompoundTag snapshot = registries.getCompoundOrEmpty(key);
        CompoundTag archive = root.getCompoundOrEmpty("basepower:legacy_registry_indexes");
        if (archive.contains(key) && !snapshot.equals(archive.get(key))) {
            throw new IllegalStateException("Conflicting historical enchantment registry indexes; keep the world backup");
        }
        archive.put(key, snapshot.copy());
        root.put("basepower:legacy_registry_indexes", archive);
        registries.remove(key);
        return true;
    }

    private static void preserveRetiredRegistryArchive(File levelDat, CompoundTag archive) throws IOException {
        if (archive.isEmpty()) return;
        Path target = levelDat.toPath().resolveSibling("basepower-retired-registry-indexes.dat");
        if (Files.exists(target)) {
            if (!archive.equals(NbtIo.readCompressed(target, NbtAccounter.unlimitedHeap()))) {
                throw new IOException("Conflicting retained registry indexes in " + target);
            }
            return;
        }
        Path temporary = Files.createTempFile(target.getParent(), "basepower-registry-indexes-", ".tmp");
        NbtIo.writeCompressed(archive, temporary);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target);
        }
        PowerAdvantage.LOGGER.info("Retained historical Forge registry indexes in {}", target);
    }

    static boolean renameFlattenedBiomeRegistry(CompoundTag root) {
        // Uppercase FML remains the untouched pre-flattening registry archive.
        if (root.getCompoundOrEmpty("Data").getIntOr("DataVersion", 0) < 1519) return false;
        CompoundTag registries = root.getCompoundOrEmpty("fml").getCompoundOrEmpty("Registries");
        String old = "minecraft:biomes", current = "minecraft:worldgen/biome";
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(registries, old, 10)) return false;
        CompoundTag snapshot = registries.getCompoundOrEmpty(old);
        if (registries.contains(current) && !snapshot.equals(registries.get(current))) {
            throw new IllegalStateException("Conflicting old and current biome registry snapshots; preserve the world backup and resolve the registry conflict before loading");
        }
        registries.put(current, snapshot);
        registries.remove(old);
        return true;
    }

    static boolean removeRetiredVanillaProfessionRegistry(CompoundTag root) {
        boolean changed = false;
        for (String namespace : new String[] {"fml", "FML"}) {
            CompoundTag registries = root.getCompoundOrEmpty(namespace).getCompoundOrEmpty("Registries");
            String key = "minecraft:villagerprofessions";
            if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(registries, key, 10)) continue;
            CompoundTag snapshot = registries.getCompoundOrEmpty(key);
            if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(snapshot, "ids", 9)) continue;
            boolean vanillaOnly = true;
            for (Tag entry : (ListTag) snapshot.get("ids")) {
                if (!(entry instanceof CompoundTag) || !Arrays.asList("minecraft:farmer", "minecraft:librarian",
                        "minecraft:priest", "minecraft:smith", "minecraft:butcher", "minecraft:nitwit")
                        .contains(((CompoundTag) entry).getStringOr("K", ""))) vanillaOnly = false;
            }
            for (String list : new String[] {"aliases", "overrides", "dummied"}) {
                if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(snapshot, list, 9) && !((ListTag) snapshot.get(list)).isEmpty()) vanillaOnly = false;
            }
            if (snapshot.getIntArray("blocked").orElseGet(() -> new int[0]).length > 0 || !vanillaOnly) continue;
            // Vanilla fixes the villagers and offers. Remove only the retired vanilla ID index.
            registries.remove(key);
            changed = true;
        }
        return changed;
    }

    /** Called by the coremod immediately after a legacy chunk NBT is read. */
    public static synchronized void prepareLegacyChunk(CompoundTag root) {
        if (root == null) return;
        migrateLegacyNbt(root);
        rememberLegacyChunk(root);
    }

    static synchronized boolean prepareExternalPayload(CompoundTag payload, boolean player) {
        CompoundTag before = player ? payload.copy() : null;
        int changed = migrateLegacyNbt(payload);
        if (player) LegacyEntityDataFixes.preparePlayerAttributes(payload);
        return changed > 0 || (player && !before.equals(payload));
    }

    static synchronized void rememberLegacyChunk(CompoundTag root) {
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "Level", Tag.TAG_COMPOUND)) return;
        CompoundTag level = root.getCompoundOrEmpty("Level");
        remapFlattenedPalette(level);
        if (!legacyWorldActive) return;
        int legacyBlocks = countLegacyBlocks(level);
        if (legacyBlocks > 0) {
            LEGACY_BLOCK_COUNTS.put(Long.valueOf(chunkKey(level.getIntOr("xPos", 0), level.getIntOr("zPos", 0))),
                    Integer.valueOf(legacyBlocks));
        }
        level.putBoolean("TerrainPopulated", true);
        level.putBoolean("LightPopulated", true);
        level.putBoolean(PRESERVE_CHUNK_MARKER, true);
    }

    static void remapFlattenedPalette(CompoundTag level) {
        for (Tag section : level.getListOrEmpty("Sections")) {
            for (Tag state : ((CompoundTag) section).getListOrEmpty("Palette")) {
                CompoundTag blockState = (CompoundTag) state;
                Identifier original = safeId(blockState.getStringOr("Name", ""));
                if (original == null) continue;
                Identifier target = LegacyMigrationApi.blockTarget(original);
                if (target.equals(original)) continue;
                if (!ForgeRegistries.BLOCKS.containsKey(target)) {
                    throw new IllegalStateException("No replacement for saved Advantage block " + original);
                }
                blockState.putString("Name", target.toString());
            }
        }
    }

    /** Detaches pending-save data before either mod's migration hook can mutate it. */
    public static CompoundTag copyChunkForMigration(CompoundTag root) {
        return root == null ? null : root.copy();
    }

    public static java.util.concurrent.CompletableFuture<java.util.Optional<CompoundTag>> copyChunkReadForMigration(
            java.util.concurrent.CompletableFuture<java.util.Optional<CompoundTag>> read) {
        return read.thenApply(chunk -> chunk.map(LegacyWorldDataHook::copyChunkForMigration));
    }

    /** Native 1.17 stores entities separately; never mutate an I/O worker's cached data. */
    public static CompoundTag prepareEntityChunkForMigration(CompoundTag source) {
        if (source == null || !zone.moddev.mc.basepower.util.NbtCompat.containsTag(source, "Position", 11)
                || source.getIntArray("Position").orElseGet(() -> new int[0]).length != 2 || !zone.moddev.mc.basepower.util.NbtCompat.containsTag(source, "Entities", 9)) {
            throw new IllegalStateException("Malformed saved entity chunk; refusing unsafe construction");
        }
        CompoundTag root = source.copy();
        migrateLegacyNbt(root);
        LegacyEntityDataFixes.prepare(root);
        return root;
    }

    public static CompoundTag upgradeEntityChunk(
            net.minecraft.world.level.chunk.storage.SimpleRegionStorage storage, CompoundTag source, int fallbackVersion) {
        return finalizeLegacyChunk(storage.upgradeChunkTag(prepareEntityChunkForMigration(source), fallbackVersion));
    }

    /** Called after all mods' entry hooks, immediately before native chunk datafixing. */
    public static void prepareLegacyEntities(CompoundTag root) {
        if (root != null) {
            LegacyMachineItemDataFixes.prepare(root);
            LegacyEntityDataFixes.prepare(root);
        }
    }

    /** Called after vanilla datafixing, before a legacy chunk is returned. */
    public static CompoundTag finalizeLegacyChunk(CompoundTag root) {
        LegacyEntityDataFixes.finish(root);
        LegacyMachineItemDataFixes.finish(root);
        if (root != null) {
            CompoundTag level = zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "Level", 10) ? root.getCompoundOrEmpty("Level") : root;
            if (level.getBooleanOr(PRESERVE_CHUNK_MARKER, false)) {
                // Let vanilla fill newly added underground space without rewriting the old surface.
                if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(level, "below_zero_retrogen", 10)) level.putString("Status", "full");
                level.remove(PRESERVE_CHUNK_MARKER);
            }
        }
        return root;
    }

    public static boolean shouldBlockWorldgenWrite(net.minecraft.server.level.WorldGenRegion region,
            net.minecraft.core.BlockPos position) {
        return legacyWorldActive && position != null
                && isProtectedChunk(region.getLevel().dimension().identifier(), position);
    }

    static boolean isProtectedChunk(Identifier dimension, net.minecraft.core.BlockPos position) {
        Set<Long> chunks = LEGACY_CHUNKS.get(dimension);
        // Do not block the native 1.18 underground extension or newly added height.
        return position.getY() >= 0 && position.getY() < 256 && chunks != null
                && chunks.contains(chunkKey(position.getX() >> 4, position.getZ() >> 4));
    }

    private static synchronized void prepareLegacyData(File worldDirectory, CompoundTag tag) {
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(tag, "Registries", 10)) return;
        CompoundTag registries = tag.getCompoundOrEmpty("Registries");
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(registries, BLOCK_REGISTRY.toString(), 10)) return;
        installLegacyEnchantments(registries);
        String key = worldKey(worldDirectory);
        boolean first = PREPARED_WORLDS.add(key);
        int states = installLegacyBlockStates(registries.getCompoundOrEmpty(BLOCK_REGISTRY.toString()));
        legacyWorldActive = states > 0;
        int chunks = indexLegacyChunks(worldDirectory);
        if (first && states > 0) {
            PowerAdvantage.LOGGER.info("Prepared {} legacy Advantage states and protected {} existing chunks in '{}'",
                    Integer.valueOf(states), Integer.valueOf(chunks), worldDirectory);
            writeMigrationReport(worldDirectory, states, chunks);
        }
    }

    private static int installLegacyBlockStates(CompoundTag snapshot) {
        LEGACY_BLOCK_IDS.clear();
        Map<Identifier, Integer> ids = new LinkedHashMap<>();
        ListTag savedIds = snapshot.getListOrEmpty("ids");
        int highestState = 0;
        for (int index = 0; index < savedIds.size(); index++) {
            CompoundTag entry = savedIds.getCompoundOrEmpty(index);
            Identifier id = safeId(normalizeLegacyRegistryName(entry.getStringOr("K", "")));
            if (id == null || !LegacyMigrationApi.handlesNamespace(id.getNamespace())) continue;
            int numeric = entry.getIntOr("V", 0);
            if (numeric < 0 || numeric > 4095) {
                throw new IllegalStateException("Invalid legacy block ID for " + id + ": " + numeric);
            }
            ids.put(id, Integer.valueOf(numeric));
            LEGACY_BLOCK_IDS.set(numeric);
            highestState = Math.max(highestState, (numeric << 4) | 15);
        }
        if (ids.isEmpty()) return 0;

        Dynamic<?>[] table = expandFlatteningTable(highestState + 1);
        int mapped = 0;
        for (Map.Entry<Identifier, Integer> entry : ids.entrySet()) {
            Identifier target = LegacyMigrationApi.blockTarget(entry.getKey());
            Block block = ForgeRegistries.BLOCKS.getValue(target);
            if (!ForgeRegistries.BLOCKS.containsKey(target) || block == null) {
                throw new IllegalStateException("No Advantage replacement for legacy block " + entry.getKey()
                        + " (expected " + target + ")");
            }
            for (int metadata = 0; metadata < 16; metadata++) {
                BlockState state = LegacyMigrationApi.decodeBlockState(entry.getKey(), block, metadata);
                table[(entry.getValue().intValue() << 4) | metadata] =
                        new Dynamic<>(net.minecraft.nbt.NbtOps.INSTANCE, NbtUtils.writeBlockState(state));
                mapped++;
            }
        }
        return mapped;
    }

    static synchronized void prepareLegacyEnchantmentContext(CompoundTag levelSnapshot) {
        // Base Metals can flatten entity inventories before our selected-world hook.
        // Resolve their numeric enchantments from this world's index, never a previous load.
        installLegacyEnchantments(levelSnapshot.getCompoundOrEmpty("FML").getCompoundOrEmpty("Registries"));
    }

    private static void installLegacyEnchantments(CompoundTag registries) {
        LEGACY_ENCHANTMENTS.clear();
        ListTag entries = registries.getCompoundOrEmpty("minecraft:enchantments").getListOrEmpty("ids");
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompoundOrEmpty(index);
            Identifier name = safeId(entry.getStringOr("K", ""));
            if (name != null) LEGACY_ENCHANTMENTS.put(Integer.valueOf(entry.getIntOr("V", 0)), name.toString());
        }
    }

    /** Resolve saved IDs, not today's registry order or vanilla's conflicting numeric IDs. */
    static boolean migrateLegacyEnchantments(CompoundTag compound, Map<Integer, String> savedIds) {
        boolean changed = migrateEnchantmentList(compound, "StoredEnchantments", savedIds);
        changed |= migrateEnchantmentList(compound, "Enchantments", savedIds);
        if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(compound, "ench", 9)) {
            ListTag enchantments = compound.getListOrEmpty("ench");
            if (!enchantments.isEmpty() && !savedIds.isEmpty()) {
                // A partial conversion would leave an unreadable mixed old/new list.
                for (int index = 0; index < enchantments.size(); index++) {
                    CompoundTag enchantment = enchantments.getCompoundOrEmpty(index);
                    if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(enchantment, "id", 99)
                            && !savedIds.containsKey(Integer.valueOf(enchantment.getIntOr("id", 0)))) {
                        throw new IllegalStateException("Missing saved enchantment mapping for " + enchantment.getIntOr("id", 0));
                    }
                }
                migrateEnchantmentList(compound, "ench", savedIds);
                compound.put("Enchantments", enchantments);
                compound.remove("ench");
                changed = true;
            }
        }
        return changed;
    }

    private static boolean migrateEnchantmentList(CompoundTag compound, String key, Map<Integer, String> savedIds) {
        boolean changed = false;
        ListTag list = compound.getListOrEmpty(key);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag enchantment = list.getCompoundOrEmpty(index);
            boolean numeric = zone.moddev.mc.basepower.util.NbtCompat.containsTag(enchantment, "id", 99);
            String name = numeric ? savedIds.get(Integer.valueOf(enchantment.getIntOr("id", 0)))
                    : enchantment.getStringOr("id", "");
            Identifier id = safeId(name);
            if (id == null) continue;
            String target = LegacyMigrationApi.registryTarget(id).toString();
            if (numeric || !target.equals(name)) {
                enchantment.putString("id", target);
                changed = true;
            }
        }
        return changed;
    }

    private static void migrateLoosePlayerData(File levelDat, CompoundTag root) throws IOException {
        boolean changed = migrateLegacyNbt(root) > 0;
        if (changed && levelDat.isFile()) writeWithBackup(levelDat, root);
        // Native file fixing moves playerdata to players/data; reopen must handle the new location too.
        for (String directory : List.of("playerdata", "players/data")) {
            File[] files = new File(levelDat.getParentFile(), directory)
                    .listFiles((parent, name) -> name.endsWith(".dat"));
            if (files == null) continue;
            Arrays.sort(files);
            for (File file : files) {
                try (FileInputStream input = new FileInputStream(file)) {
                    CompoundTag player = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                    if (migrateLegacyNbt(player) > 0) writeWithBackup(file, player);
                }
            }
        }
    }

    private static int migrateLegacyNbt(Tag value) {
        int changed = 0;
        if (value instanceof CompoundTag) {
            CompoundTag compound = (CompoundTag) value;
            String id = compound.getStringOr("id", "");
            String vanillaBlockEntity = VANILLA_BLOCK_ENTITY_IDS.get(id);
            if (vanillaBlockEntity != null) {
                compound.putString("id", vanillaBlockEntity);
                id = vanillaBlockEntity;
                changed++;
            }

            String tileTarget = LegacyMigrationApi.tileTarget(id);
            String entityTarget = LegacyMigrationApi.entityTarget(tileTarget);
            if (!entityTarget.equals(normalizeIdentity(id))) {
                compound.putString("id", entityTarget);
                id = entityTarget;
                changed++;
            }

            if ("forge:bucketfilled".equalsIgnoreCase(id) && zone.moddev.mc.basepower.util.NbtCompat.containsTag(compound, "tag", 10)) {
                CompoundTag tag = compound.getCompoundOrEmpty("tag");
                Identifier bucket = LegacyMigrationApi.bucketTarget(tag.getStringOr("FluidName", ""));
                if (bucket != null) {
                    compound.putString("id", bucket.toString());
                    tag.remove("FluidName");
                    tag.remove("Amount");
                    if (tag.isEmpty()) compound.remove("tag");
                    changed++;
                }
            } else if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(compound, "Count", 99)) {
                Identifier oldId = safeId(id);
                if (oldId != null && LegacyMigrationApi.handlesNamespace(oldId.getNamespace())) {
                    Identifier target = LegacyMigrationApi.itemTarget(oldId);
                    if (!target.equals(oldId)) {
                        if (!ForgeRegistries.ITEMS.containsKey(target)) {
                            throw new IllegalStateException("No replacement for saved Advantage item "
                                    + oldId + " (expected " + target + ")");
                        }
                        compound.putString("id", target.toString());
                        changed++;
                    }
                    if (LegacyMigrationApi.isDurableItem(oldId)
                            && zone.moddev.mc.basepower.util.NbtCompat.containsTag(compound, "Damage", 99) && compound.getIntOr("Damage", 0) > 0) {
                        CompoundTag tag = zone.moddev.mc.basepower.util.NbtCompat.containsTag(compound, "tag", 10)
                                ? compound.getCompoundOrEmpty("tag") : new CompoundTag();
                        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(tag, "Damage", 99)) {
                            tag.putInt("Damage", compound.getIntOr("Damage", 0));
                            compound.put("tag", tag);
                            changed++;
                        }
                    }
                }
            }

            changed += migrateFluidName(compound, "FluidName");
            changed += migrateFluidName(compound, "FluidID");
            if (migrateLegacyEnchantments(compound, LEGACY_ENCHANTMENTS)) changed++;
            if (LegacyMigrationApi.migrateRegisteredNbt(compound)) changed++;
            for (String key : new ArrayList<>(compound.keySet())) {
                Tag child = compound.get(key);
                if (child != null) changed += migrateLegacyNbt(child);
            }
        } else if (value instanceof ListTag) {
            ListTag list = (ListTag) value;
            for (int index = 0; index < list.size(); index++) changed += migrateLegacyNbt(list.get(index));
        }
        return changed;
    }

    private static int migrateFluidName(CompoundTag compound, String key) {
        if (!zone.moddev.mc.basepower.util.NbtCompat.containsTag(compound, key, 8)) return 0;
        String oldName = compound.getStringOr(key, "");
        String target = LegacyMigrationApi.fluidTarget(oldName);
        if (target.equals(normalizeIdentity(oldName))) return 0;
        compound.putString(key, target);
        return 1;
    }

    static File legacyRegistrySource(File levelDat) {
        File sidecar = new File(levelDat.getParentFile(), REGISTRY_SIDECAR);
        if (sidecar.isFile()) return sidecar;
        for (File candidate : new File[] { levelDat, new File(levelDat.getParentFile(), "level.dat_old") }) {
            if (!candidate.isFile()) continue;
            try (FileInputStream input = new FileInputStream(candidate)) {
                CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(root, "FML", 10) && containsLegacyRegistryEntry(root)) return candidate;
            } catch (IOException ignored) {}
        }
        return null;
    }

    static void preserveLegacyRegistry(File levelDat, CompoundTag source) throws IOException {
        java.nio.file.Path target = levelDat.toPath().resolveSibling(REGISTRY_SIDECAR);
        if (Files.exists(target)) return;
        // Forge no longer writes the uppercase FML archive. Unvisited legacy chunks still need it.
        CompoundTag archive = new CompoundTag();
        archive.put("FML", source.getCompoundOrEmpty("FML").copy());
        java.nio.file.Path temporary = Files.createTempFile(target.getParent(), "basepower-legacy-", ".tmp");
        try {
            NbtIo.writeCompressed(archive, temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean containsLegacyRegistryEntry(CompoundTag root) {
        ListTag ids = root.getCompoundOrEmpty("FML").getCompoundOrEmpty("Registries")
                .getCompoundOrEmpty(BLOCK_REGISTRY.toString()).getListOrEmpty("ids");
        for (int index = 0; index < ids.size(); index++) {
            Identifier id = safeId(normalizeLegacyRegistryName(ids.getCompoundOrEmpty(index).getStringOr("K", "")));
            if (id != null && LegacyMigrationApi.handlesNamespace(id.getNamespace())) return true;
        }
        return false;
    }

    private static int countLegacyBlocks(CompoundTag level) {
        int found = 0;
        ListTag sections = level.getListOrEmpty("Sections");
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            CompoundTag section = sections.getCompoundOrEmpty(sectionIndex);
            byte[] blocks = section.getByteArray("Blocks").orElseGet(() -> new byte[0]);
            if (blocks.length != 4096) continue;
            byte[] add = section.getByteArray("Add").orElseGet(() -> new byte[0]);
            for (int index = 0; index < blocks.length; index++) {
                int high = add.length == 2048 ? (add[index >> 1] >> ((index & 1) * 4)) & 15 : 0;
                if (LEGACY_BLOCK_IDS.get((blocks[index] & 255) | (high << 8))) found++;
            }
        }
        return found;
    }

    /** Snapshot used by migration probes after spawn chunks have loaded. */
    public static Map<Long, Integer> legacyPreparedBlockCounts() {
        return new LinkedHashMap<>(LEGACY_BLOCK_COUNTS);
    }

    static int indexLegacyChunks(File worldDirectory) {
        LEGACY_CHUNKS.clear();
        int indexed = indexLegacyChunks(Identifier.parse("overworld"), new File(worldDirectory, "region"))
                + indexLegacyChunks(Identifier.parse("the_nether"), new File(worldDirectory, "DIM-1/region"))
                + indexLegacyChunks(Identifier.parse("the_end"), new File(worldDirectory, "DIM1/region"));
        Path dimensions = worldDirectory.toPath().resolve("dimensions");
        if (Files.isDirectory(dimensions)) {
            try (var directories = Files.walk(dimensions)) {
                for (Path directory : directories.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().equals("region")).toList()) {
                    Path relative = dimensions.relativize(directory.getParent());
                    if (relative.getNameCount() < 2) continue;
                    Identifier dimension = Identifier.tryParse(relative.getName(0) + ":"
                            + relative.subpath(1, relative.getNameCount()).toString().replace(File.separatorChar, '/'));
                    if (dimension != null) indexed += indexLegacyChunks(dimension, directory.toFile());
                }
            } catch (IOException exception) {
                throw new IllegalStateException("Could not inspect saved dimension paths in " + dimensions, exception);
            }
        }
        return indexed;
    }

    private static int indexLegacyChunks(Identifier dimension, File regionDirectory) {
        File[] regions = regionDirectory.listFiles((directory, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mc[ar]"));
        if (regions == null) return 0;
        Set<Long> chunks = LEGACY_CHUNKS.computeIfAbsent(dimension, ignored -> ConcurrentHashMap.newKeySet());
        int previous = chunks.size();
        byte[] locations = new byte[4096];
        for (File region : regions) {
            String[] parts = region.getName().split("\\.");
            if (parts.length != 4) continue;
            try (InputStream input = Files.newInputStream(region.toPath())) {
                int read = 0;
                while (read < locations.length) {
                    int count = input.read(locations, read, locations.length - read);
                    if (count < 0) break;
                    read += count;
                }
                int regionX = Integer.parseInt(parts[1]);
                int regionZ = Integer.parseInt(parts[2]);
                for (int index = 0; index < read / 4; index++) {
                    int offset = index * 4;
                    if ((locations[offset] | locations[offset + 1] | locations[offset + 2] | locations[offset + 3]) != 0) {
                        chunks.add(Long.valueOf(chunkKey(
                                regionX * 32 + (index & 31), regionZ * 32 + (index >> 5))));
                    }
                }
            } catch (IOException | NumberFormatException exception) {
                PowerAdvantage.LOGGER.warn("Could not inspect legacy chunk locations in '{}'", region, exception);
            }
        }
        LEGACY_CHUNKS.put(dimension, chunks);
        return chunks.size() - previous;
    }

    @SuppressWarnings("unchecked")
    private static Dynamic<?>[] expandFlatteningTable(int requiredLength) {
        try {
            Field valuesField = BlockStateData.class.getDeclaredField("MAP");
            valuesField.setAccessible(true);
            if (Modifier.isFinal(valuesField.getModifiers())) {
                throw new IllegalStateException("The Advantage flattening-table hook did not run");
            }
            Dynamic<?>[] current = (Dynamic<?>[]) valuesField.get(null);
            if (current.length >= requiredLength) return current;
            Dynamic<?>[] expanded = Arrays.copyOf(current, requiredLength);
            valuesField.set(null, expanded);
            return expanded;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Could not expand Minecraft's legacy block-state flattening table", exception);
        }
    }

    private static void writeWithBackup(File file, CompoundTag data) throws IOException {
        File backup = new File(file.getParentFile(), file.getName() + ".basepower-legacy-backup");
        if (!backup.exists()) Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        File temporary = new File(file.getParentFile(), file.getName() + ".basepower.tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            NbtIo.writeCompressed(data, output);
        }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeMigrationReport(File worldDirectory, int states, int chunks) {
        File report = new File(worldDirectory, "basepower-migration-report.txt");
        if (report.exists()) return;
        List<String> lines = Arrays.asList(
                "Advantage 1.14 legacy migration",
                "Prepared block states: " + states,
                "Protected existing chunks: " + chunks,
                "Namespaces: " + String.join(", ", LegacyMigrationApi.namespaces()),
                "Source files were backed up before any loose NBT mutation.");
        try {
            Files.write(report.toPath(), lines, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            PowerAdvantage.LOGGER.warn("Could not write migration report '{}'", report, exception);
        }
    }

    private static String normalizeLegacyRegistryName(String name) {
        String normalized = normalizeIdentity(name);
        return normalized.indexOf(':') < 0 ? PowerAdvantage.MODID + ":" + normalized : normalized;
    }

    private static String normalizeIdentity(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static Identifier safeId(String value) {
        try { return value == null || value.isEmpty() ? null : Identifier.parse(value.toLowerCase(Locale.ROOT)); }
        catch (RuntimeException ignored) { return null; }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x & 0xffffffffL) << 32 | ((long) z & 0xffffffffL);
    }

    private static String worldKey(File directory) {
        try { return directory.getCanonicalPath(); }
        catch (IOException exception) { return directory.getAbsolutePath(); }
    }

    private static Map<String, String> vanillaBlockEntityIds() {
        Map<String, String> ids = new LinkedHashMap<>();
        ids.put("Airportal", "minecraft:end_portal");
        ids.put("Banner", "minecraft:banner");
        ids.put("Beacon", "minecraft:beacon");
        ids.put("Cauldron", "minecraft:brewing_stand");
        ids.put("Chest", "minecraft:chest");
        ids.put("Comparator", "minecraft:comparator");
        ids.put("Control", "minecraft:command_block");
        ids.put("DLDetector", "minecraft:daylight_detector");
        ids.put("Dropper", "minecraft:dropper");
        ids.put("EnchantTable", "minecraft:enchanting_table");
        ids.put("EndGateway", "minecraft:end_gateway");
        ids.put("EnderChest", "minecraft:ender_chest");
        ids.put("FlowerPot", "minecraft:flower_pot");
        ids.put("Furnace", "minecraft:furnace");
        ids.put("Hopper", "minecraft:hopper");
        ids.put("MobSpawner", "minecraft:mob_spawner");
        ids.put("Music", "minecraft:noteblock");
        ids.put("Piston", "minecraft:piston");
        ids.put("RecordPlayer", "minecraft:jukebox");
        ids.put("Sign", "minecraft:sign");
        ids.put("Skull", "minecraft:skull");
        ids.put("Structure", "minecraft:structure_block");
        ids.put("Trap", "minecraft:dispenser");
        return Collections.unmodifiableMap(ids);
    }
}
