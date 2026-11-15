package zone.moddev.mc.advantageworks;

import java.util.function.Consumer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Keeps the retained probes' before/after-tick contract on native NeoForge events. */
final class WorksLevelTick {
    enum Phase { START, END }
    final Level level;
    final Phase phase;

    private WorksLevelTick(Level level, Phase phase) { this.level = level; this.phase = phase; }

    static void listen(IEventBus bus, Consumer<WorksLevelTick> consumer) {
        bus.addListener((LevelTickEvent.Pre event) -> consumer.accept(new WorksLevelTick(event.getLevel(), Phase.START)));
        bus.addListener((LevelTickEvent.Post event) -> consumer.accept(new WorksLevelTick(event.getLevel(), Phase.END)));
    }
}
