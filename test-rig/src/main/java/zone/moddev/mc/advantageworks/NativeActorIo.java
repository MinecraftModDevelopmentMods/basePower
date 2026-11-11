package zone.moddev.mc.advantageworks;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

/** Native registry-aware persistence; partial codec results are never witnesses. */
final class NativeActorIo {
    private NativeActorIo() {}

    static CompoundTag save(Entity entity) {
        TagValueOutput output = output(entity);
        if (!entity.save(output)) throw new IllegalStateException("Native entity save refused " + entity.getUUID());
        return output.buildResult();
    }

    static CompoundTag saveWithoutId(Entity entity) {
        TagValueOutput output = output(entity);
        entity.saveWithoutId(output);
        return output.buildResult();
    }

    static CompoundTag saveAsPassenger(Entity entity) {
        TagValueOutput output = output(entity);
        if (!entity.saveAsPassenger(output)) throw new IllegalStateException("Native passenger save refused " + entity.getUUID());
        return output.buildResult();
    }

    static void load(Entity entity, CompoundTag nbt) {
        entity.load(TagValueInput.create(reporter("load " + entity.getUUID()), entity.registryAccess(), nbt));
    }

    private static TagValueOutput output(Entity entity) {
        return TagValueOutput.createWithContext(reporter("save " + entity.getUUID()), entity.registryAccess());
    }

    static ProblemReporter reporter(String path) {
        return new ProblemReporter() {
            @Override public ProblemReporter forChild(ProblemReporter.PathElement child) {
                return reporter(path + child.get());
            }

            @Override public void report(ProblemReporter.Problem problem) {
                throw new IllegalStateException("Native actor persistence problem at " + path + ": " + problem.description());
            }
        };
    }
}
