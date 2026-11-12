package zone.moddev.mc.advantageworks;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

/** Holds real native ticker registrations without replacing tiles or writing state. */
final class NativeBlockEntityTicks {
    private NativeBlockEntityTicks() {}

    private static List<TickingBlockEntity> active(Level world) {
        return ObfuscationReflectionHelper.getPrivateValue(Level.class, world, "blockEntityTickers");
    }

    private static List<TickingBlockEntity> pending(Level world) {
        return ObfuscationReflectionHelper.getPrivateValue(Level.class, world, "pendingBlockEntityTickers");
    }

    static List<TickingBlockEntity> registrations(Level world) {
        List<TickingBlockEntity> result = new ArrayList<>(active(world));
        result.addAll(pending(world));
        return java.util.Collections.unmodifiableList(result);
    }

    static List<TickingBlockEntity> drain(Level world) {
        List<TickingBlockEntity> held = new ArrayList<>(active(world));
        for (TickingBlockEntity entry : pending(world)) if (!held.contains(entry)) held.add(entry);
        active(world).clear();
        pending(world).clear();
        return held;
    }

    static void restore(Level world, List<TickingBlockEntity> held) {
        for (TickingBlockEntity entry : held) {
            if (!entry.isRemoved() && !active(world).contains(entry) && !pending(world).contains(entry)) {
                world.addBlockEntityTicker(entry);
            }
        }
        held.clear();
    }

    static boolean contains(Level world, BlockEntity actual) {
        if (actual == null || actual.isRemoved() || world.getBlockEntity(actual.getBlockPos()) != actual) return false;
        return find(active(world), actual) != null || find(pending(world), actual) != null;
    }

    static void remove(Level world, BlockEntity actual) {
        if (world.getBlockEntity(actual.getBlockPos()) != actual) {
            throw new AssertionError("Measured tile is not the native world's current instance");
        }
        TickingBlockEntity first = find(active(world), actual), second = find(pending(world), actual);
        if ((first == null ? 0 : 1) + (second == null ? 0 : 1) != 1) {
            throw new AssertionError("Expected one native measured ticker registration");
        }
        if (first != null) SteamReloadControlCases.removeMeasuredTile(active(world), first);
        if (second != null) SteamReloadControlCases.removeMeasuredTile(pending(world), second);
        if (contains(world, actual)) throw new AssertionError("Measured tile remained tickable");
    }

    private static TickingBlockEntity find(List<TickingBlockEntity> entries, BlockEntity actual) {
        TickingBlockEntity found = null;
        for (TickingBlockEntity entry : entries) {
            if (!entry.isRemoved() && entry.getPos().equals(actual.getBlockPos())) {
                if (found != null) throw new AssertionError("Duplicate native ticker at " + actual.getBlockPos());
                found = entry;
            }
        }
        return found;
    }
}
