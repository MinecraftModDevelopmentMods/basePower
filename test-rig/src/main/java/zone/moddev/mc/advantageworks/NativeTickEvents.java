package zone.moddev.mc.advantageworks;

import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;

/** Keeps each fixture's pre/post observation boundary explicit on Forge's split event buses. */
final class NativeTickEvents {
    enum Phase { START, END }

    record LevelTickEvent(Level level, Phase phase) {}
    record ServerTickEvent(MinecraftServer server, Phase phase) {}

    private NativeTickEvents() {}

    static void listenLevel(Consumer<LevelTickEvent> observer) {
        TickEvent.LevelTickEvent.Pre.BUS.addListener(event ->
                observer.accept(new LevelTickEvent(event.level(), Phase.START)));
        TickEvent.LevelTickEvent.Post.BUS.addListener(event ->
                observer.accept(new LevelTickEvent(event.level(), Phase.END)));
    }

    static void listenServer(Consumer<ServerTickEvent> observer) {
        TickEvent.ServerTickEvent.Pre.BUS.addListener(event ->
                observer.accept(new ServerTickEvent(event.server(), Phase.START)));
        TickEvent.ServerTickEvent.Post.BUS.addListener(event ->
                observer.accept(new ServerTickEvent(event.server(), Phase.END)));
    }
}
