package zone.moddev.mc.nativeplatform;

import java.nio.file.Path;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.LeashFenceKnotEntity;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativePlatformControlsTest {
    @Test public void nativeLeashKnotStorageEligibilityWithoutServerOrTicks() throws Exception {
        NativeLeashKnotPlatformControl.main(new String[] {
                Path.of(System.getProperty("native.control.evidenceRoot"), "leash-knot").toString() });
    }

    @Test public void nativeWolfLoadSaveAndLegacyDfuWithoutServerOrTicks() throws Exception {
        NativeWolfPlatformControl.main(new String[] {
                Path.of(System.getProperty("native.control.evidenceRoot"), "wolf").toString() });
    }

    @Test public void nativeHashGuardRejectsForge52AndTamperedHashes() throws Exception {
        NeoControlBootstrap.verifyNativeClassHashes();
        Class<?>[] types = {Entity.class, EntityType.class, LeashFenceKnotEntity.class};
        String[] forge52 = {
            "D7BC1CA192106808495C6A4928CA69B02B06509B47D0A44587EED8BF6A590B05",
            "C0058623DD898DB6E3B9423A7F009ABF37D48DEF0FFBFCFBA62F330D31CE0E7A",
            "8D5AB259B502457FEE5E55CA01F966E453088AB07D827BFF773F16AEC3D423E9"
        };
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            String hash = forge52[i];
            AssertionError error = assertThrows(AssertionError.class, () -> NeoControlBootstrap.requireNativeHash(type, hash));
            assertTrue(error.getMessage().contains("differs from the NeoForge development jar"));
        }
        assertThrows(AssertionError.class, () -> NeoControlBootstrap.requireNativeHash(Entity.class, "0".repeat(64)));
    }
}
