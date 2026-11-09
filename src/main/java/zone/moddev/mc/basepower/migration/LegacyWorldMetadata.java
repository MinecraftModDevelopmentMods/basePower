package zone.moddev.mc.basepower.migration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import cyano.poweradvantage.api.migration.LegacyMigrationApi;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

/** Translate renamed mods before vanilla selects packs or Forge reads its saved mod list. */
public final class LegacyWorldMetadata {
    private LegacyWorldMetadata() {}

    public static CompoundTag read(File levelDat) throws IOException {
        CompoundTag root = NbtIo.readCompressed(levelDat);
        remap(root, ModList.get()::isLoaded);
        return root;
    }

    static void remap(CompoundTag root, Predicate<String> loaded) {
        ListTag mods = root.getCompound("fml").getList("LoadingModList", Tag.TAG_COMPOUND);
        Set<String> recordedMods = new HashSet<>();
        for (int i = 0; i < mods.size(); ++i) recordedMods.add(mods.getCompound(i).getString("ModId"));
        for (int i = 0; i < mods.size(); ++i) {
            CompoundTag mod = mods.getCompound(i);
            String id = mod.getString("ModId");
            String target = target(id, loaded);
            if (target.equals(id)) continue;
            if (recordedMods.add(target)) mod.putString("ModId", target);
            else mods.remove(i--);
        }

        CompoundTag packs = root.getCompound("Data").getCompound("DataPacks");
        ListTag enabled = packs.getList("Enabled", Tag.TAG_STRING);
        ListTag disabled = packs.getList("Disabled", Tag.TAG_STRING);
        Set<String> recordedPacks = new HashSet<>();
        enabled.forEach(pack -> recordedPacks.add(pack.getAsString()));
        disabled.forEach(pack -> recordedPacks.add(pack.getAsString()));
        remapPacks(enabled, recordedPacks, loaded);
        remapPacks(disabled, recordedPacks, loaded);
    }

    private static void remapPacks(ListTag packs, Set<String> recorded, Predicate<String> loaded) {
        for (int i = 0; i < packs.size(); ++i) {
            String pack = packs.getString(i);
            if (!pack.startsWith("mod:")) continue;
            String replacement = "mod:" + target(pack.substring(4), loaded);
            if (replacement.equals(pack)) continue;
            if (recorded.add(replacement)) packs.set(i, StringTag.valueOf(replacement));
            else packs.remove(i--);
        }
    }

    private static String target(String id, Predicate<String> loaded) {
        if (id.isEmpty() || id.indexOf(':') >= 0) return id;
        ResourceLocation key = ResourceLocation.tryParse(id + ":metadata");
        if (key == null) return id;
        String replacement = LegacyMigrationApi.registryTarget(key).getNamespace();
        return !replacement.equals(id) && loaded.test(replacement) && !loaded.test(id) ? replacement : id;
    }
}
