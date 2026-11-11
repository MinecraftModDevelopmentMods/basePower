package zone.moddev.mc.advantageworks;

import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;

final class Native117Worlds {
    private Native117Worlds() {}

    static ResourceKey<Level> key(int legacyId) {
        switch (legacyId) {
            case -1: return Level.NETHER;
            case 0: return Level.OVERWORLD;
            case 1: return Level.END;
            default: throw new IllegalArgumentException("Unsupported fixture dimension: " + legacyId);
        }
    }

    static int legacyId(ResourceKey<Level> key) {
        if (key.equals(Level.NETHER)) return -1;
        if (key.equals(Level.OVERWORLD)) return 0;
        if (key.equals(Level.END)) return 1;
        throw new IllegalArgumentException("Unsupported native fixture dimension: " + key.identifier());
    }

    static Stream<Entity> entities(ServerLevel world) {
        return StreamSupport.stream(world.getAllEntities().spliterator(), false);
    }
}
