package zone.moddev.mc.basepower.compat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.fml.common.FMLLog;

/** Converts only Forge's saved oil owners, before Forge reads the world header. */
public final class LegacyLevelData {
    private LegacyLevelData() { }

    public static boolean remapFluidDefaults(NBTTagCompound root) {
        if (!root.hasKey("Forge", 10)) return false;
        NBTTagCompound forge = root.getCompoundTag("Forge");
        if (!forge.hasKey("DefaultFluidList", 9)) return false;
        NBTTagList original = forge.getTagList("DefaultFluidList", 8);
        NBTTagList replacement = new NBTTagList();
        boolean changed = false;
        for (int index = 0; index < original.tagCount(); index++) {
            String before = original.getStringTagAt(index);
            String after = before;
            if ("poweradvantage:crude_oil".equals(before)) after = "basepower:crude_oil";
            if ("poweradvantage:refined_oil".equals(before)) after = "basepower:refined_oil";
            replacement.appendTag(new NBTTagString(after));
            changed |= !before.equals(after);
        }
        if (changed) forge.setTag("DefaultFluidList", replacement);
        return changed;
    }

    public static void migrateWorld(Path worldRoot) throws IOException {
        migrateWorld(worldRoot, new HashSet<>(Arrays.asList("basepower", "basesteam", "baseelectric")));
    }

    public static boolean remapModList(NBTTagCompound root, Set<String> loadedReplacements) {
        if (!root.hasKey("FML", 10)) return false;
        NBTTagList mods = root.getCompoundTag("FML").getTagList("ModList", 10);
        Set<String> present = new HashSet<>();
        for (int index = 0; index < mods.tagCount(); index++) present.add(mods.getCompoundTagAt(index).getString("ModId"));
        String[][] pairs = { { "poweradvantage", "basepower" }, { "steamadvantage", "basesteam" },
                { "electricadvantage", "baseelectric" } };
        for (String[] pair : pairs) {
            if (loadedReplacements.contains(pair[1]) && present.contains(pair[0]) && present.contains(pair[1])) {
                throw new IllegalStateException("World header contains both " + pair[0] + " and " + pair[1]);
            }
        }
        boolean changed = false;
        for (int index = 0; index < mods.tagCount(); index++) {
            NBTTagCompound mod = mods.getCompoundTagAt(index);
            for (String[] pair : pairs) {
                if (loadedReplacements.contains(pair[1]) && pair[0].equals(mod.getString("ModId"))) {
                    mod.setString("ModId", pair[1]);
                    changed = true;
                }
            }
        }
        return changed;
    }

    public static void migrateWorld(Path worldRoot, Set<String> loadedReplacements) throws IOException {
        Path root = worldRoot.toAbsolutePath().normalize();
        for (String name : new String[] { "level.dat", "level.dat_old" }) {
            Path file = root.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)
                    || Files.size(file) > 64 * 1024 * 1024) {
                throw new IOException("Refusing an invalid world header: " + file);
            }
            byte[] original = Files.readAllBytes(file);
            NBTTagCompound document;
            try (InputStream input = new java.io.ByteArrayInputStream(original)) {
                document = CompressedStreamTools.readCompressed(input);
            }
            boolean changed = remapFluidDefaults(document);
            changed |= remapModList(document, loadedReplacements);
            if (!changed) continue;
            Path backups = root.resolve("basepower-migration-backups");
            Files.createDirectories(backups);
            Path backup = backups.resolve(name + "." + sha256(original) + ".before-base-identity-migration");
            if (!Files.exists(backup)) Files.copy(file, backup);
            if (!Arrays.equals(original, Files.readAllBytes(backup))) {
                throw new IOException("World-header backup does not match: " + backup);
            }
            if (!Arrays.equals(original, Files.readAllBytes(file))) {
                throw new IOException("World header changed during migration: " + file);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            CompressedStreamTools.writeCompressed(document, output);
            Path temporary = Files.createTempFile(root, ".basepower-level-", ".tmp");
            try {
                Files.write(temporary, output.toByteArray());
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
            FMLLog.info("basepower: migrated saved Advantage identities in %s; original header retained at %s", file, backup);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
