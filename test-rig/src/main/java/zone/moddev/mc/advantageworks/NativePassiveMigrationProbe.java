package zone.moddev.mc.advantageworks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** Observation only: never constructs, refills, repairs or rewrites a saved witness. */
final class NativePassiveMigrationProbe {
    private static final File MARKER = new File("advantage-passive-migration.allow");
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static NativePassiveMigrationProbe instance;
    private final NativeEntityHold actors = new NativeEntityHold();
    private final Map<TileEntity, Boolean> tiles = new IdentityHashMap<>();

    static void register() {
        if (!MARKER.isFile()) return;
        if (new File("migration-operate.allow").exists() || new File("advantage-machine-operation.allow").exists())
            throw new IllegalStateException("Passive migration cannot coexist with operating authority");
        instance = new NativePassiveMigrationProbe();
        MinecraftForge.EVENT_BUS.register(instance);
    }

    private void hold(World world) {
        if (world.isRemote) return;
        if (!"AdvantageWorksMigration".equals(world.getWorldInfo().getWorldName()))
            throw new IllegalStateException("Unexpected passive fixture world");
        for (TileEntity tile : world.tickableTileEntities) tiles.put(tile, Boolean.TRUE);
        world.tickableTileEntities.clear();
        for (Entity entity : new ArrayList<>(world.loadedEntityList))
            if (!(entity instanceof EntityPlayer)) actors.hold(entity);
    }

    @SubscribeEvent public void worldLoaded(WorldEvent.Load event) { hold(event.getWorld()); }
    @SubscribeEvent public void chunkLoaded(ChunkEvent.Load event) { hold(event.getWorld()); }
    @SubscribeEvent public void tick(TickEvent.WorldTickEvent event) {
        if (event.world.isRemote) return;
        if (event.phase == TickEvent.Phase.START) hold(event.world);
        else actors.restore();
    }

    static void registerCommands(FMLServerStartingEvent event) {
        if (instance == null) return;
        event.registerServerCommand(new CommandBase() {
            @Override public String getName() { return "advpassive"; }
            @Override public String getUsage(ICommandSender sender) { return "advpassive snapshot <label>"; }
            @Override public int getRequiredPermissionLevel() { return 4; }
            @Override public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
                try {
                    if (args.length != 2 || !"snapshot".equals(args[0]) || !args[1].matches("[A-Za-z0-9_-]+"))
                        throw new IllegalArgumentException(getUsage(sender));
                    instance.snapshot(server.worldServerForDimension(0), args[1]);
                } catch (Exception error) { throw new IllegalStateException("Passive probe failed", error); }
            }
        });
        NativeNestedIdentityProbe.registerCommands(event);
    }

    private void snapshot(WorldServer world, String label) throws Exception {
        hold(world);
        JsonArray definition = JSON.fromJson(new String(Files.readAllBytes(new File("migration-fixture.json").toPath()),
                StandardCharsets.UTF_8), JsonArray.class);
        if (definition.size() != 355) throw new IllegalStateException("Expected the complete authentic 355-witness fixture");
        JsonArray rows = new JsonArray();
        for (JsonElement element : definition) {
            JsonObject declared = element.getAsJsonObject();
            BlockPos pos = new BlockPos(declared.get("x").getAsInt(), declared.get("y").getAsInt(), declared.get("z").getAsInt());
            IBlockState state = world.getBlockState(pos);
            JsonObject row = new JsonObject();
            for (String field : new String[]{"x", "y", "z", "name"}) row.add(field, declared.get(field));
            row.addProperty("metadata", state.getBlock().getMetaFromState(state));
            row.addProperty("actualName", state.getBlock().getRegistryName().toString());
            JsonObject properties = new JsonObject();
            for (Map.Entry<IProperty<?>, Comparable<?>> property : state.getProperties().entrySet())
                properties.addProperty(property.getKey().getName(), property.getValue().toString().toLowerCase(java.util.Locale.ROOT));
            row.add("properties", properties);
            TileEntity tile = world.getTileEntity(pos);
            if (tile != null) row.addProperty("nbtBase64", NativeNbt.encode(tile.writeToNBT(new NBTTagCompound())));
            rows.add(row);
        }
        hold(world);
        JsonObject report = new JsonObject();
        report.addProperty("scope", "AUTHENTIC_ORIGINAL_RELEASE_110_PASSIVE_355");
        report.addProperty("held", world.tickableTileEntities.isEmpty());
        report.addProperty("constructionCallsThisProcess", 0);
        report.addProperty("requestedTicks", 0);
        report.addProperty("advancedTicks", 0);
        report.addProperty("frozenTileObjects", tiles.size());
        report.add("snapshot", rows);
        JsonArray enchantments = new JsonArray();
        for (ResourceLocation name : Enchantment.REGISTRY.getKeys()) {
            Enchantment enchantment = Enchantment.REGISTRY.getObject(name);
            if (!name.equals(Enchantment.REGISTRY.getNameForObject(enchantment))) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("name", name.toString());
            entry.addProperty("numericId", Enchantment.getEnchantmentID(enchantment));
            enchantments.add(entry);
        }
        report.add("enchantments", enchantments);
        write(new File("passive-" + label + ".json"), report);
        System.out.println("ADVPASSIVE COMPLETE " + label);
    }

    static void write(File path, JsonObject report) throws Exception {
        Files.write(path.toPath(), JSON.toJson(report).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}
