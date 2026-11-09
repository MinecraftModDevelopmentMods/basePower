package zone.moddev.mc.advantageworks;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/** Observes the inner native dispatch; an outer ticker invocation can be skipped. */
final class LifecycleNaturalTickObserver implements AutoCloseable {
    final BlockEntity tile;
    final TickingBlockEntity registration;
    final Object bound;
    final Field bindingField, delegateField;
    final BlockEntityTicker<BlockEntity> original;
    final Capture capture = new Capture();
    private BlockEntityTicker<BlockEntity> observer;

    @SuppressWarnings("unchecked")
    LifecycleNaturalTickObserver(Level world, BlockEntity tile) {
        this.tile = tile;
        List<TickingBlockEntity> found = new ArrayList<>();
        for (TickingBlockEntity entry : NativeBlockEntityTicks.registrations(world)) {
            if (!entry.isRemoved() && entry.getPos().equals(tile.getBlockPos())) found.add(entry);
        }
        if (found.size() != 1) throw new IllegalStateException("Expected exactly one native ticker registration");
        registration = found.get(0);
        requireNativeInner(registration.getClass());
        bindingField = field(registration.getClass(), TickingBlockEntity.class);
        bound = get(bindingField, registration);
        requireNativeInner(bound.getClass());
        if (get(field(bound.getClass(), BlockEntity.class), bound) != tile)
            throw new IllegalStateException("Native ticker binds another tile instance");
        delegateField = field(bound.getClass(), BlockEntityTicker.class);
        original = (BlockEntityTicker<BlockEntity>) get(delegateField, bound);
        if (original == null) throw new IllegalStateException("Native ticker has no delegate");
    }

    private static void requireNativeInner(Class<?> type) {
        if (type.getEnclosingClass() != LevelChunk.class)
            throw new IllegalStateException("Not a native LevelChunk ticker binding: " + type.getName());
    }

    private static Field field(Class<?> type, Class<?> fieldType) {
        Field selected = null;
        for (Field field : type.getDeclaredFields()) {
            if (field.getType() != fieldType) continue;
            if (selected != null) throw new IllegalStateException("Ambiguous native ticker layout");
            selected = field;
        }
        if (selected == null) throw new IllegalStateException("Unknown native ticker layout");
        selected.setAccessible(true);
        return selected;
    }

    private static Object get(Field field, Object owner) {
        try { return field.get(owner); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    private void set(Object value) {
        try { delegateField.set(bound, value); }
        catch (IllegalAccessException error) { throw new IllegalStateException(error); }
    }

    boolean current(Level world) {
        return NativeBlockEntityTicks.registrations(world).stream().filter(entry -> entry == registration).count() == 1
                && get(bindingField, registration) == bound && !registration.isRemoved()
                && tile.getLevel() == world && !tile.isRemoved();
    }

    void install(Level expectedWorld, long callbackTime, BooleanSupplier inWindow,
                 Supplier<Map<String, Object>> witness, Supplier<Map<String, Boolean>> prerequisites) {
        if (observer != null || get(delegateField, bound) != original || !current(expectedWorld))
            throw new IllegalStateException("Changed or already observed native binding");
        Thread expectedThread = Thread.currentThread();
        observer = (world, pos, state, actual) -> {
            List<String> stack = new ArrayList<>();
            boolean nativeBound = false, nativeLoop = false;
            for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
                // Use explicit fields so module/loader prefixes cannot change the receipt format.
                stack.add(frame.getClassName() + "." + frame.getMethodName()
                        + "(" + frame.getFileName() + ":" + frame.getLineNumber() + ")");
                nativeBound |= frame.getClassName().equals(bound.getClass().getName());
                nativeLoop |= nativeLoopFrame(frame);
            }
            boolean natural = nativeBound && nativeLoop;
            Map<String, Boolean> actualFlags = prerequisites.get();
            boolean valid = natural && world == expectedWorld && actual == tile && pos.equals(tile.getBlockPos())
                    && Thread.currentThread() == expectedThread && world.getGameTime() == callbackTime
                    && inWindow.getAsBoolean() && current(world) && tile.getType().isValid(state)
                    && actualFlags.values().stream().allMatch(Boolean.TRUE::equals);
            boolean first = capture.enter(world.getGameTime(), valid, natural, stack, witness.get());
            if (first) capture.prerequisites = new LinkedHashMap<>(actualFlags);
            // Forward only the callback the native BoundTickingBlockEntity just dispatched.
            // Commands and phase observers never invoke this delegate themselves.
            original.tick(world, pos, state, actual);
            capture.exit(first, witness.get());
        };
        set(observer);
    }

    static boolean nativeLoopFrame(StackTraceElement frame) {
        return frame.getClassName().equals(Level.class.getName())
                && ("tickBlockEntities".equals(frame.getMethodName()) || "m_46463_".equals(frame.getMethodName()));
    }

    Map<String, Object> report() {
        Map<String, Object> result = capture.report();
        result.put("registrationIdentity", System.identityHashCode(registration));
        result.put("boundIdentity", System.identityHashCode(bound));
        result.put("delegateIdentity", System.identityHashCode(original));
        result.put("delegateClass", original.getClass().getName());
        result.put("boundClass", bound.getClass().getName());
        result.put("tileClass", tile.getClass().getName());
        result.put("tileCodeSource", String.valueOf(tile.getClass().getProtectionDomain().getCodeSource()));
        return result;
    }

    @Override public void close() {
        if (observer == null) return;
        if (get(delegateField, bound) != observer) throw new IllegalStateException("Observed native delegate was replaced");
        set(original);
        observer = null;
    }

    static final class Capture {
        int calls, completed;
        long worldTime;
        boolean valid, nativeStackValidated;
        List<String> nativeStack;
        Map<String, Object> before, after;
        Map<String, Boolean> prerequisites;

        boolean enter(long time, boolean eligible, boolean natural, List<String> stack, Map<String, Object> state) {
            calls++;
            if (calls != 1) return false;
            worldTime = time;
            valid = eligible;
            nativeStackValidated = natural;
            nativeStack = new ArrayList<>(stack);
            before = state;
            return true;
        }

        void exit(boolean first, Map<String, Object> state) {
            completed++;
            if (first) after = state;
        }

        boolean complete() { return calls == 1 && completed == 1 && valid && nativeStackValidated && after != null; }

        Map<String, Object> report() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("calls", calls); result.put("completed", completed);
            result.put("valid", valid); result.put("nativeStackValidated", nativeStackValidated);
            result.put("worldTime", worldTime); result.put("nativeStack", nativeStack);
            result.put("before", before); result.put("after", after);
            result.put("prerequisiteFlags", prerequisites);
            return result;
        }
    }
}
