import com.google.gson.Gson;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.ForgeMod;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/** Native load/save control: no Advantage classes, server, entity ticks or DFU. */
public final class NativeWolfPlatformControl {
    private static final UUID OWNER = UUID.fromString("a614e658-6270-33c3-9297-7ce80908964f");
    private static final UUID ID = UUID.fromString("a6ea8abd-6ab3-4e66-8ef5-d713aabbccdd");

    private static void field(ServerLevel world, String name, Object value) throws Exception {
        Field field = Level.class.getDeclaredField(name); field.setAccessible(true); field.set(world, value);
    }

    private static ServerLevel context() throws Exception {
        Class<?> unsafe = Class.forName("sun.misc.Unsafe");
        Field instance = unsafe.getDeclaredField("theUnsafe"); instance.setAccessible(true);
        ServerLevel world = (ServerLevel) unsafe.getMethod("allocateInstance", Class.class)
                .invoke(instance.get(null), ServerLevel.class);
        field(world, "dimension", Level.NETHER);
        field(world, "random", new Random(0));
        field(world, "profiler", (Supplier<ProfilerFiller>) () -> InactiveProfiler.INSTANCE);
        return world;
    }

    private static Wolf wolf(ServerLevel world) {
        Wolf wolf = new Wolf(EntityType.WOLF, world); wolf.setUUID(ID); return wolf;
    }

    private static void control(Path root, ServerLevel world, boolean tame, float health) throws Exception {
        String label = (tame ? "tame" : "wild") + "-health-" + (int) health;
        Wolf first = wolf(world);
        first.setTame(tame);
        if (tame) first.setOwnerUUID(OWNER);
        first.setHealth(health);
        CompoundTag before = first.saveWithoutId(new CompoundTag());
        if (before.getFloat("Health") != health) throw new AssertionError("Incorrect source control health");
        Path input = root.resolve(label + "-before.dat"); NbtIo.writeCompressed(before, input.toFile());
        Wolf second = wolf(world); second.load(NbtIo.readCompressed(input.toFile()));
        CompoundTag after = second.saveWithoutId(new CompoundTag());
        NbtIo.writeCompressed(after, root.resolve(label + "-after.dat").toFile());
        Wolf third = wolf(world); third.load(after.copy());
        CompoundTag restarted = third.saveWithoutId(new CompoundTag());
        NbtIo.writeCompressed(restarted, root.resolve(label + "-restart.dat").toFile());
        if (!ID.equals(second.getUUID()) || !ID.equals(third.getUUID())) throw new AssertionError("Control UUID changed");
        if (tame && (!OWNER.equals(second.getOwnerUUID()) || !OWNER.equals(third.getOwnerUUID()))) {
            throw new AssertionError("Control owner changed");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label); row.put("currentWorldDataVersion", SharedConstants.getCurrentVersion().getWorldVersion());
        row.put("legacyDfuInvoked", false); row.put("directNativeEntityLoadSave", true);
        row.put("beforeHealth", before.getFloat("Health")); row.put("afterHealth", after.getFloat("Health"));
        row.put("restartHealth", restarted.getFloat("Health"));
        row.put("uuidPreserved", true); row.put("ownerPreserved", !tame || OWNER.equals(second.getOwnerUUID()));
        System.out.println("WOLF_CONTROL " + new Gson().toJson(row));
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        Field attributes = ForgeMod.class.getDeclaredField("ATTRIBUTES"); attributes.setAccessible(true);
        Method add = DeferredRegister.class.getDeclaredMethod("addEntries", RegistryEvent.Register.class); add.setAccessible(true);
        add.invoke(attributes.get(null), new RegistryEvent.Register<>(ForgeRegistries.ATTRIBUTES.getRegistryName(), ForgeRegistries.ATTRIBUTES));
        try {
            Class.forName("zone.moddev.mc.poweradvantage.migration.LegacyEntityDataFixes");
            throw new AssertionError("Advantage is present on the native control classpath");
        } catch (ClassNotFoundException expected) { System.out.println("POWER_LEGACY_CLASS_ABSENT true"); }
        Path root = Paths.get(args[0]); Files.createDirectory(root);
        System.out.println("NATIVE_WOLF_CODE " + Wolf.class.getProtectionDomain().getCodeSource().getLocation());
        ServerLevel world = context();
        control(root, world, true, 8); control(root, world, true, 20); control(root, world, false, 8);
        System.out.println("WOLF_CONTROL_DONE no_server=true no_ticks=true native_entity_methods_unmodified=true");
    }
}
