package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.event.RegisterCommandsEvent;

/** Read-only state witnesses in explicitly marked disposable migration worlds. */
final class MigrationProbe {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File MARKER = new File("advantage-migration.allow");

    static void register() {
        if (!MARKER.isFile()) return;
        MigrationProbe probe = new MigrationProbe();
        MinecraftForge.EVENT_BUS.addListener(probe::load);
        MinecraftForge.EVENT_BUS.addListener(probe::tick);
        MinecraftForge.EVENT_BUS.addListener(probe::chunkLoaded);
        MinecraftForge.EVENT_BUS.addListener(probe::starting);
    }

    private void load(WorldEvent.Load event) {
        if (event.getWorld() instanceof Level) freeze((Level) event.getWorld());
    }
    private void tick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.START) freeze(event.world);
    }
    private void chunkLoaded(net.minecraftforge.event.world.ChunkEvent.Load event) {
        if (event.getWorld() instanceof Level) freeze((Level) event.getWorld());
    }
    private void freeze(Level world) {
        if (!world.isClientSide && !new File("migration-operate.allow").isFile()) NativeBlockEntityTicks.drain(world);
    }

    private void starting(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("advprobe")
                .requires(source -> source.hasPermission(4))
                .then(Commands.literal("snapshot")
                        .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                            try {
                                snapshot(context.getSource().getLevel(), StringArgumentType.getString(context, "label"));
                                System.out.println("ADVPROBE COMPLETE snapshot");
                                return 1;
                            } catch (Exception ex) { throw new IllegalStateException("Migration probe failed", ex); }
                        }))));
    }

    private void snapshot(Level world, String label) throws Exception {
        if (!MARKER.isFile() || !world.getServer().getWorldData().getLevelName().equals("AdvantageWorksMigration")) {
            throw new IllegalStateException("Refusing an unmarked or unexpected world");
        }
        if (!label.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid snapshot label");
        JsonArray definition = JSON.fromJson(new String(Files.readAllBytes(new File("migration-fixture.json").toPath()),
                StandardCharsets.UTF_8), JsonArray.class);
        JsonArray result = new JsonArray();
        for (JsonElement element : definition) {
            JsonObject entry = JSON.fromJson(JSON.toJson(element), JsonObject.class);
            BlockPos pos = new BlockPos(entry.get("x").getAsInt(), entry.get("y").getAsInt(), entry.get("z").getAsInt());
            BlockState state = world.getBlockState(pos);
            entry.addProperty("actualName", state.getBlock().getRegistryName().toString());
            JsonObject properties = new JsonObject();
            for (Property<?> property : state.getProperties()) {
                properties.addProperty(property.getName(), state.getValue(property).toString().toLowerCase(Locale.ROOT));
            }
            entry.add("properties", properties);
            BlockEntity tile = world.getBlockEntity(pos);
            if (tile != null) {
                tile.setChanged();
                CompoundTag nbt = tile.save(new CompoundTag());
                entry.addProperty("nbt", nbt.toString());
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                net.minecraft.nbt.NbtIo.writeCompressed(nbt, bytes);
                entry.addProperty("nbtBase64", java.util.Base64.getEncoder().encodeToString(bytes.toByteArray()));
            }
            result.add(entry);
        }
        Files.write(new File("migration-" + label + ".json").toPath(), JSON.toJson(result).getBytes(StandardCharsets.UTF_8));
    }
}
