package zone.moddev.mc.advantageworks;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import org.junit.Test;

public class NativeEntityDataTest {
    @Test public void acceptsOnlyTheExactVerifiedNativeField() throws Exception {
        Field nativeField = Entity.class.getDeclaredField("persistentData");
        assertSame(nativeField, NativeEntityData.requireNativeField(nativeField));
        assertThrows(IllegalStateException.class, () -> NativeEntityData.requireNativeField(null));
        assertThrows(IllegalStateException.class,
                () -> NativeEntityData.requireNativeField(Entity.class.getDeclaredField("level")));
        assertThrows(IllegalStateException.class,
                () -> NativeEntityData.requireNativeField(Impostor.class.getDeclaredField("persistentData")));
    }

    @Test public void missingEntityFailsClosedInsteadOfMatching() {
        assertThrows(NullPointerException.class, () -> NativeEntityData.containsString(null, "tag"));
        assertThrows(NullPointerException.class, () -> NativeEntityData.string(null, "tag"));
    }

    private static final class Impostor {
        private CompoundTag persistentData;
    }
}
