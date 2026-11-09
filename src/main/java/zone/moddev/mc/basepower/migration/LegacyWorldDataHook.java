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

import cpw.mods.modlauncher.api.INameMappingService;
import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.registries.ForgeRegistries;
import zone.moddev.mc.basepower.PowerAdvantage;

/**
 * Shared Advantage pre-flattening migration hook for Minecraft 1.10 and 1.12
 * worlds. Add-ons contribute their identities through {@link LegacyMigrationApi}.
 */
public final class LegacyWorldDataHook {
    private static final ResourceLocation BLOCK_REGISTRY = new ResourceLocation("minecraft", "blocks");
    private static final Set<String> PREPARED_WORLDS = ConcurrentHashMap.newKeySet();
    private static final String REGISTRY_SIDECAR = "basepower-legacy-registry.dat";
    private static final BitSet LEGACY_BLOCK_IDS = new BitSet();
    private static final Map<Integer, String> LEGACY_ENCHANTMENTS = new LinkedHashMap<>();
    private static final Map<ResourceLocation, Set<Long>> LEGACY_CHUNKS = new ConcurrentHashMap<>();
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
        prepareForgeRegistryMetadata(levelDat);
        File source = legacyRegistrySource(levelDat);
        try {
            if (source != null) {
                try (FileInputStream input = new FileInputStream(source)) {
                    CompoundTag registryArchive = NbtIo.readCompressed(input);
                    if (!containsLegacyRegistryEntry(registryArchive)) {
                        throw new IOException("Invalid retained Advantage registry snapshot " + source);
                    }
                    preserveLegacyRegistry(levelDat, registryArchive);
                    prepareLegacyData(levelDat.getParentFile(), registryArchive.getCompound("FML"));
                }
            }
            if (levelDat.isFile()) {
                // A backup can supply registry IDs, but never replace newer world data.
                try (FileInputStream current = new FileInputStream(levelDat)) {
                    CompoundTag root = NbtIo.readCompressed(current);
                    if (source != null || hasAdvantageNamespaceAliases(root)) migrateLoosePlayerData(levelDat, root);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare legacy Advantage data in " + levelDat, exception);
        }
    }

    private static boolean hasAdvantageNamespaceAliases(CompoundTag root) {
        CompoundTag registries = root.getCompound("fml").getCompound("Registries");
        for (String registry : registries.getAllKeys()) {
            for (Tag entry : registries.getCompound(registry).getList("ids", Tag.TAG_COMPOUND)) {
                ResourceLocation id = safeId(((CompoundTag) entry).getString("K"));
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
                root = NbtIo.readCompressed(input);
            }
            boolean changed = removeRetiredVanillaProfessionRegistry(root);
            changed |= renameFlattenedBiomeRegistry(root);
            if (changed) writeWithBackup(levelDat, root);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not prepare the old villager registry in " + levelDat, exception);
        }
    }

    static boolean renameFlattenedBiomeRegistry(CompoundTag root) {
        // Uppercase FML remains the untouched pre-flattening registry archive.
        if (root.getCompound("Data").getInt("DataVersion") < 1519) return false;
        CompoundTag registries = root.getCompound("fml").getCompound("Registries");
        String old = "minecraft:biomes", current = "minecraft:worldgen/biome";
        if (!registries.contains(old, 10)) return false;
        CompoundTag snapshot = registries.getCompound(old);
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
            CompoundTag registries = root.getCompound(namespace).getCompound("Registries");
            String key = "minecraft:villagerprofessions";
            if (!registries.contains(key, 10)) continue;
            CompoundTag snapshot = registries.getCompound(key);
            if (!snapshot.contains("ids", 9)) continue;
            boolean vanillaOnly = true;
            for (Tag entry : (ListTag) snapshot.get("ids")) {
                if (!(entry instanceof CompoundTag) || !Arrays.asList("minecraft:farmer", "minecraft:librarian",
                        "minecraft:priest", "minecraft:smith", "minecraft:butcher", "minecraft:nitwit")
                        .contains(((CompoundTag) entry).getString("K"))) vanillaOnly = false;
            }
            for (String list : new String[] {"aliases", "overrides", "dummied"}) {
                if (snapshot.contains(list, 9) && !((ListTag) snapshot.get(list)).isEmpty()) vanillaOnly = false;
            }
            if (snapshot.getIntArray("blocked").length > 0 || !vanillaOnly) continue;
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
        if (!root.contains("Level", Tag.TAG_COMPOUND)) return;
        CompoundTag level = root.getCompound("Level");
        remapFlattenedPalette(level);
        if (!legacyWorldActive) return;
        int legacyBlocks = countLegacyBlocks(level);
        if (legacyBlocks > 0) {
            LEGACY_BLOCK_COUNTS.put(Long.valueOf(chunkKey(level.getInt("xPos"), level.getInt("zPos"))),
                    Integer.valueOf(legacyBlocks));
        }
        level.putBoolean("TerrainPopulated", true);
        level.putBoolean("LightPopulated", true);
        level.putBoolean(PRESERVE_CHUNK_MARKER, true);
    }

    static void remapFlattenedPalette(CompoundTag level) {
        for (Tag section : level.getList("Sections", Tag.TAG_COMPOUND)) {
            for (Tag state : ((CompoundTag) section).getList("Palette", Tag.TAG_COMPOUND)) {
                CompoundTag blockState = (CompoundTag) state;
                ResourceLocation original = safeId(blockState.getString("Name"));
                if (original == null) continue;
                ResourceLocation target = LegacyMigrationApi.blockTarget(original);
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

    /** Native 1.17 stores entities separately; never mutate an I/O worker's cached data. */
    public static CompoundTag prepareEntityChunkForMigration(CompoundTag source) {
        if (source == null || !source.contains("Position", 11)
                || source.getIntArray("Position").length != 2 || !source.contains("Entities", 9)) {
            throw new IllegalStateException("Malformed saved entity chunk; refusing unsafe construction");
        }
        CompoundTag root = source.copy();
        migrateLegacyNbt(root);
        LegacyEntityDataFixes.prepare(root);
        return root;
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
            CompoundTag level = root.contains("Level", 10) ? root.getCompound("Level") : root;
            if (level.getBoolean(PRESERVE_CHUNK_MARKER)) {
                // Let vanilla fill newly added underground space without rewriting the old surface.
                if (!level.contains("below_zero_retrogen", 10)) level.putString("Status", "full");
                level.remove(PRESERVE_CHUNK_MARKER);
            }
        }
        return root;
    }

    public static boolean shouldBlockWorldgenWrite(net.minecraft.server.level.WorldGenRegion region,
            net.minecraft.core.BlockPos position) {
        return legacyWorldActive && position != null
                && isProtectedChunk(region.getLevel().dimension().location(), position);
    }

    static boolean isProtectedChunk(ResourceLocation dimension, net.minecraft.core.BlockPos position) {
        Set<Long> chunks = LEGACY_CHUNKS.get(dimension);
        // Do not block the native 1.18 underground extension or newly added height.
        return position.getY() >= 0 && position.getY() < 256 && chunks != null
                && chunks.contains(chunkKey(position.getX() >> 4, position.getZ() >> 4));
    }

    private static synchronized void prepareLegacyData(File worldDirectory, CompoundTag tag) {
        if (!tag.contains("Registries", 10)) return;
        CompoundTag registries = tag.getCompound("Registries");
        if (!registries.contains(BLOCK_REGISTRY.toString(), 10)) return;
        installLegacyEnchantments(registries);
        String key = worldKey(worldDirectory);
        boolean first = PREPARED_WORLDS.add(key);
        int states = installLegacyBlockStates(registries.getCompound(BLOCK_REGISTRY.toString()));
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
        Map<ResourceLocation, Integer> ids = new LinkedHashMap<>();
        ListTag savedIds = snapshot.getList("ids", 10);
        int highestState = 0;
        for (int index = 0; index < savedIds.size(); index++) {
            CompoundTag entry = savedIds.getCompound(index);
            ResourceLocation id = safeId(normalizeLegacyRegistryName(entry.getString("K")));
            if (id == null || !LegacyMigrationApi.handlesNamespace(id.getNamespace())) continue;
            int numeric = entry.getInt("V");
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
        for (Map.Entry<ResourceLocation, Integer> entry : ids.entrySet()) {
            ResourceLocation target = LegacyMigrationApi.blockTarget(entry.getKey());
            Block block = ForgeRegistries.BLOCKS.getValue(target);
            if (!ForgeRegistries.BLOCKS.containsKey(target) || block == null) {
                throw new IllegalStateException("No Advantage replacement for legacy block " + entry.getKey()
                        + " (expected " + target + ")");
            }
            for (int metadata = 0; metadata < 16; metadata++) {
                BlockState state = LegacyMigrationApi.decodeBlockState(entry.getKey(), block, metadata);
                table[(entry.getValue().intValue() << 4) | metadata] =
                        BlockStateData.parse(NbtUtils.writeBlockState(state).toString());
                mapped++;
            }
        }
        return mapped;
    }

    private static void installLegacyEnchantments(CompoundTag registries) {
        LEGACY_ENCHANTMENTS.clear();
        ListTag entries = registries.getCompound("minecraft:enchantments").getList("ids", 10);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            ResourceLocation name = safeId(entry.getString("K"));
            if (name != null) LEGACY_ENCHANTMENTS.put(Integer.valueOf(entry.getInt("V")), name.toString());
        }
    }

    /** Resolve saved IDs, not today's registry order or vanilla's conflicting numeric IDs. */
    static boolean migrateLegacyEnchantments(CompoundTag compound, Map<Integer, String> savedIds) {
        boolean changed = migrateEnchantmentList(compound, "StoredEnchantments", savedIds);
        changed |= migrateEnchantmentList(compound, "Enchantments", savedIds);
        if (compound.contains("ench", 9)) {
            ListTag enchantments = compound.getList("ench", 10);
            if (!enchantments.isEmpty() && !savedIds.isEmpty()) {
                // A partial conversion would leave an unreadable mixed old/new list.
                for (int index = 0; index < enchantments.size(); index++) {
                    CompoundTag enchantment = enchantments.getCompound(index);
                    if (enchantment.contains("id", 99)
                            && !savedIds.containsKey(Integer.valueOf(enchantment.getInt("id")))) {
                        throw new IllegalStateException("Missing saved enchantment mapping for " + enchantment.getInt("id"));
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
        ListTag list = compound.getList(key, 10);
        for (int index = 0; index < list.size(); index++) {
            CompoundTag enchantment = list.getCompound(index);
            boolean numeric = enchantment.contains("id", 99);
            String name = numeric ? savedIds.get(Integer.valueOf(enchantment.getInt("id")))
                    : enchantment.getString("id");
            ResourceLocation id = safeId(name);
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
        File playerData = new File(levelDat.getParentFile(), "playerdata");
        File[] files = playerData.listFiles((directory, name) -> name.endsWith(".dat"));
        if (files == null) return;
        Arrays.sort(files);
        for (File file : files) {
            try (FileInputStream input = new FileInputStream(file)) {
                CompoundTag player = NbtIo.readCompressed(input);
                if (migrateLegacyNbt(player) > 0) writeWithBackup(file, player);
            }
        }
    }

    private static int migrateLegacyNbt(Tag value) {
        int changed = 0;
        if (value instanceof CompoundTag) {
            CompoundTag compound = (CompoundTag) value;
            String id = compound.getString("id");
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

            if ("forge:bucketfilled".equalsIgnoreCase(id) && compound.contains("tag", 10)) {
                CompoundTag tag = compound.getCompound("tag");
                ResourceLocation bucket = LegacyMigrationApi.bucketTarget(tag.getString("FluidName"));
                if (bucket != null) {
                    compound.putString("id", bucket.toString());
                    tag.remove("FluidName");
                    tag.remove("Amount");
                    if (tag.isEmpty()) compound.remove("tag");
                    changed++;
                }
            } else if (compound.contains("Count", 99)) {
                ResourceLocation oldId = safeId(id);
                if (oldId != null && LegacyMigrationApi.handlesNamespace(oldId.getNamespace())) {
                    ResourceLocation target = LegacyMigrationApi.itemTarget(oldId);
                    if (!target.equals(oldId)) {
                        if (!ForgeRegistries.ITEMS.containsKey(target)) {
                            throw new IllegalStateException("No replacement for saved Advantage item "
                                    + oldId + " (expected " + target + ")");
                        }
                        compound.putString("id", target.toString());
                        changed++;
                    }
                    if (LegacyMigrationApi.isDurableItem(oldId)
                            && compound.contains("Damage", 99) && compound.getInt("Damage") > 0) {
                        CompoundTag tag = compound.contains("tag", 10)
                                ? compound.getCompound("tag") : new CompoundTag();
                        if (!tag.contains("Damage", 99)) {
                            tag.putInt("Damage", compound.getInt("Damage"));
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
            for (String key : new ArrayList<>(compound.getAllKeys())) {
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
        if (!compound.contains(key, 8)) return 0;
        String oldName = compound.getString(key);
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
                CompoundTag root = NbtIo.readCompressed(input);
                if (root.contains("FML", 10) && containsLegacyRegistryEntry(root)) return candidate;
            } catch (IOException ignored) {}
        }
        return null;
    }

    static void preserveLegacyRegistry(File levelDat, CompoundTag source) throws IOException {
        java.nio.file.Path target = levelDat.toPath().resolveSibling(REGISTRY_SIDECAR);
        if (Files.exists(target)) return;
        // Forge no longer writes the uppercase FML archive. Unvisited legacy chunks still need it.
        CompoundTag archive = new CompoundTag();
        archive.put("FML", source.getCompound("FML").copy());
        java.nio.file.Path temporary = Files.createTempFile(target.getParent(), "basepower-legacy-", ".tmp");
        try {
            NbtIo.writeCompressed(archive, temporary.toFile());
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean containsLegacyRegistryEntry(CompoundTag root) {
        ListTag ids = root.getCompound("FML").getCompound("Registries")
                .getCompound(BLOCK_REGISTRY.toString()).getList("ids", 10);
        for (int index = 0; index < ids.size(); index++) {
            ResourceLocation id = safeId(normalizeLegacyRegistryName(ids.getCompound(index).getString("K")));
            if (id != null && LegacyMigrationApi.handlesNamespace(id.getNamespace())) return true;
        }
        return false;
    }

    private static int countLegacyBlocks(CompoundTag level) {
        int found = 0;
        ListTag sections = level.getList("Sections", 10);
        for (int sectionIndex = 0; sectionIndex < sections.size(); sectionIndex++) {
            CompoundTag section = sections.getCompound(sectionIndex);
            byte[] blocks = section.getByteArray("Blocks");
            if (blocks.length != 4096) continue;
            byte[] add = section.getByteArray("Add");
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
        return indexLegacyChunks(new ResourceLocation("overworld"), new File(worldDirectory, "region"))
                + indexLegacyChunks(new ResourceLocation("the_nether"), new File(worldDirectory, "DIM-1/region"))
                + indexLegacyChunks(new ResourceLocation("the_end"), new File(worldDirectory, "DIM1/region"));
    }

    private static int indexLegacyChunks(ResourceLocation dimension, File regionDirectory) {
        File[] regions = regionDirectory.listFiles((directory, name) -> name.matches("r\\.-?\\d+\\.-?\\d+\\.mc[ar]"));
        if (regions == null) return 0;
        Set<Long> chunks = ConcurrentHashMap.newKeySet();
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
        return chunks.size();
    }

    @SuppressWarnings("unchecked")
    private static Dynamic<?>[] expandFlatteningTable(int requiredLength) {
        try {
            String fieldName = ObfuscationReflectionHelper.remapName(INameMappingService.Domain.FIELD, "f_14934_");
            Field valuesField = BlockStateData.class.getDeclaredField(fieldName);
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

    private static ResourceLocation safeId(String value) {
        try { return value == null || value.isEmpty() ? null : new ResourceLocation(value.toLowerCase(Locale.ROOT)); }
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
