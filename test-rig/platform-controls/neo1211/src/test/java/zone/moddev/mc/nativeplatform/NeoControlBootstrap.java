package zone.moddev.mc.nativeplatform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.SharedConstants;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForgeMod;
import net.neoforged.neoforge.internal.versions.neoforge.NeoForgeVersion;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

final class NeoControlBootstrap {
    private NeoControlBootstrap() {}

    static Map<String, String> modVersions() {
        Map<String, String> mods = new TreeMap<>();
        ModList.get().getMods().forEach(mod -> mods.put(mod.getModId(), mod.getVersion().toString()));
        return mods;
    }

    static void verifyNativeRegistries() {
        if (!NeoForgeMod.WATER_TYPE.isBound() || !NeoForgeMod.LAVA_TYPE.isBound()) {
            throw new AssertionError("Native unit-test loader did not register fluid types");
        }
    }

    static void verifyNativeAttributes() {
        if (!DefaultAttributes.hasSupplier(EntityType.WOLF)) throw new AssertionError("Native wolf attributes are absent");
    }

    static void verifyNativeClassHashes() throws Exception {
        if (!"1.21.1".equals(SharedConstants.getCurrentVersion().getName()) || !"21.1.265".equals(NeoForgeVersion.getVersion())) {
            throw new AssertionError("Wrong native Minecraft/NeoForge versions");
        }
        Map<String, String> mods = modVersions();
        if (!mods.keySet().equals(Set.of("minecraft", "neoforge", "native1211neo_platform_controls"))) {
            throw new AssertionError("Non-platform mods loaded in independent control: " + mods);
        }
        for (String name : new String[] {"zone.moddev.mc.basepower.PowerAdvantage", "zone.moddev.mc.basesteam.SteamAdvantage",
                "zone.moddev.mc.baseelectric.ElectricAdvantage"}) {
            try {
                Class.forName(name, false, NeoControlBootstrap.class.getClassLoader());
                throw new AssertionError("Advantage class present in independent control: " + name);
            } catch (ClassNotFoundException expected) { }
        }
        if (ServerLifecycleHooks.getCurrentServer() != null) throw new AssertionError("A Minecraft server was started");
        JsonObject expected;
        try (InputStream input = NeoControlBootstrap.class.getResourceAsStream("/native-platform-class-sha256.json")) {
            if (input == null) throw new AssertionError("Missing native development-jar hash manifest");
            expected = JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        }
        JsonObject classes = expected.getAsJsonObject("nativeClassSha256");
        for (Class<?> type : new Class<?>[] {Entity.class, EntityType.class, LeashFenceKnotEntity.class, Wolf.class}) {
            requireNativeHash(type, classes.get(type.getName()).getAsString());
        }
        System.out.println("NATIVE_PLATFORM_MODS " + mods);
        System.out.println("NATIVE_PLATFORM_NO_ADVANTAGE_NO_SERVER true");
    }

    static void requireNativeHash(Class<?> type, String expected) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new AssertionError("Missing loaded native class bytes: " + type);
            String actual = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
            if (!actual.equals(expected)) {
                throw new AssertionError("Loaded native class differs from the NeoForge development jar: " + type + " " + actual);
            }
            System.out.println("NATIVE_NEO_CLASS_SHA256 " + type.getName() + " " + actual);
        }
    }
}
