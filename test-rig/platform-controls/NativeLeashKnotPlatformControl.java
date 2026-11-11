import com.google.gson.GsonBuilder;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import net.minecraft.world.level.Level;

/** No-Power, no-server native storage-eligibility control. */
public final class NativeLeashKnotPlatformControl {
    private static void field(ServerLevel world, String name, Object value) throws Exception {
        Field field = Level.class.getDeclaredField(name); field.setAccessible(true); field.set(world, value);
    }
    private static String hash(Class<?> type) throws Exception {
        try (InputStream input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new AssertionError("No native class bytes");
            StringBuilder result = new StringBuilder();
            for (byte value : MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())) result.append(String.format("%02X", value & 255));
            return result.toString();
        }
    }
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        zone.moddev.mc.advantageworks.unit.ForgeUnitRegistries.registerVanillaFluidTypes();
        try {
            Class.forName("zone.moddev.mc.basepower.migration.LegacyEntityDataFixes");
            throw new AssertionError("Power must be absent from the native control");
        } catch (ClassNotFoundException expected) { System.out.println("POWER_LEGACY_CLASS_ABSENT true"); }
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        ServerLevel world = (ServerLevel) unsafe.getMethod("allocateInstance", Class.class).invoke(instance.get(null), ServerLevel.class);
        field(world, "dimension", Level.NETHER); field(world, "random", RandomSource.create(0));
        field(world, "profiler", (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
        LeashFenceKnotEntity knot = new LeashFenceKnotEntity(world, new BlockPos(154, 100, 8));
        UUID identity = knot.getUUID();
        CompoundTag before = knot.saveWithoutId(new CompoundTag()).copy();
        boolean accepted = knot.saveAsPassenger(new CompoundTag());
        CompoundTag observed = knot.saveWithoutId(new CompoundTag());
        if (knot.getClass() != LeashFenceKnotEntity.class || knot.getType() != EntityType.LEASH_KNOT
                || EntityType.LEASH_KNOT.canSerialize() || accepted || !identity.equals(knot.getUUID())
                || !before.equals(observed) || knot.tickCount != 0 || knot.isRemoved()) throw new AssertionError("Native noSave contract differs");
        Map<String,Object> proof = new LinkedHashMap<>();
        proof.put("format", 1); proof.put("policy", "native1211-leash-knot-nosave-v1");
        proof.put("minecraftVersion", SharedConstants.getCurrentVersion().getName());
        proof.put("dataVersion", SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        proof.put("forgeVersion", net.minecraftforge.versions.forge.ForgeVersion.getVersion());
        proof.put("nativeClass", knot.getClass().getName()); proof.put("registryId", EntityType.getKey(knot.getType()).toString());
        proof.put("canSerialize", false); proof.put("rootWriterAccepted", false);
        proof.put("observationalUuidPreserved", true); proof.put("observationalStateUnchanged", true);
        proof.put("noPower", true); proof.put("noServer", true); proof.put("noTicks", true); proof.put("nativeMethodsUnmodified", true);
        Map<String,String> classes = new LinkedHashMap<>();
        for (Class<?> type : new Class<?>[] {Entity.class, EntityType.class, LeashFenceKnotEntity.class}) classes.put(type.getName(), hash(type));
        proof.put("nativeClassSha256", classes);
        Path root = Paths.get(args[0]); Files.createDirectory(root);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(proof) + "\n";
        Files.writeString(root.resolve("contract.json"), json);
        System.out.println("LEASH_KNOT_CONTROL " + json.replace('\n', ' '));
        System.out.println("LEASH_KNOT_CONTROL_DONE no_server=true no_ticks=true native_entity_methods_unmodified=true");
    }
}
