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
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraftforge.fml.ModList;

/** Translate renamed mods before vanilla selects packs or Forge reads its saved mod list. */
public final class LegacyWorldMetadata {
    private LegacyWorldMetadata() {}

    public static CompoundTag read(File levelDat) throws IOException {
        LegacyWorldDataHook.prepareForgeRegistryMetadata(levelDat);
        CompoundTag root = NbtIo.readCompressed(levelDat.toPath(), NbtAccounter.unlimitedHeap());
        remap(root, ModList::isLoaded);
        return root;
    }

    public static Tag remapLightweight(Tag root) {
        if (root instanceof CompoundTag compound) remap(compound, ModList::isLoaded);
        return root;
    }

    public static CompoundTag remapRaw(CompoundTag root) {
        remap(root, ModList::isLoaded);
        return root;
    }

    static void remap(CompoundTag root, Predicate<String> loaded) {
        CompoundTag forge = root.getCompoundOrEmpty("fml");
        CompoundTag registries = forge.getCompoundOrEmpty("Registries");
        if (zone.moddev.mc.basepower.util.NbtCompat.containsTag(registries, "minecraft:motive", Tag.TAG_COMPOUND)
                && !registries.contains("minecraft:painting_variant")) {
            // The registry was renamed; keep its saved IDs, including modded entries.
            registries.put("minecraft:painting_variant", registries.getCompoundOrEmpty("minecraft:motive"));
            registries.remove("minecraft:motive");
        }
        ListTag mods = typedList(forge, "LoadingModList", CompoundTag.class);
        Set<String> recordedMods = new HashSet<>();
        for (int i = 0; i < mods.size(); ++i) recordedMods.add(mods.getCompoundOrEmpty(i).getStringOr("ModId", ""));
        for (int i = 0; i < mods.size(); ++i) {
            CompoundTag mod = mods.getCompoundOrEmpty(i);
            String id = mod.getStringOr("ModId", "");
            String target = target(id, loaded);
            if (target.equals(id)) continue;
            if (recordedMods.add(target)) mod.putString("ModId", target);
            else mods.remove(i--);
        }

        CompoundTag packs = root.getCompoundOrEmpty("Data").getCompoundOrEmpty("DataPacks");
        ListTag enabled = typedList(packs, "Enabled", StringTag.class);
        ListTag disabled = typedList(packs, "Disabled", StringTag.class);
        Set<String> recordedPacks = new HashSet<>();
        enabled.forEach(pack -> recordedPacks.add(((StringTag)pack).value()));
        disabled.forEach(pack -> recordedPacks.add(((StringTag)pack).value()));
        remapPacks(enabled, recordedPacks, loaded);
        remapPacks(disabled, recordedPacks, loaded);
    }

    private static ListTag typedList(CompoundTag owner, String key, Class<? extends Tag> elementType) {
        return owner.getList(key).filter(values -> values.stream().allMatch(elementType::isInstance))
                .orElseGet(ListTag::new);
    }

    private static void remapPacks(ListTag packs, Set<String> recorded, Predicate<String> loaded) {
        for (int i = 0; i < packs.size(); ++i) {
            String pack = packs.getStringOr(i, "");
            if (!pack.startsWith("mod:")) continue;
            String replacement = "mod:" + target(pack.substring(4), loaded);
            if (replacement.equals(pack)) continue;
            if (recorded.add(replacement)) packs.set(i, StringTag.valueOf(replacement));
            else packs.remove(i--);
        }
    }

    private static String target(String id, Predicate<String> loaded) {
        if (id.isEmpty() || id.indexOf(':') >= 0) return id;
        Identifier key = Identifier.tryParse(id + ":metadata");
        if (key == null) return id;
        String replacement = LegacyMigrationApi.registryTarget(key).getNamespace();
        return !replacement.equals(id) && loaded.test(replacement) && !loaded.test(id) ? replacement : id;
    }
}
