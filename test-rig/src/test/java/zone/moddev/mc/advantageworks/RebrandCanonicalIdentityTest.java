package zone.moddev.mc.advantageworks;

import org.junit.Test;
import static org.junit.Assert.*;

public class RebrandCanonicalIdentityTest {
    @Test public void steamEnchantmentKeysStayCanonicalWithoutNewNamespaceFallback() {
        for (String name : new String[]{"rapid_reload", "powderless", "high_explosive", "recoil"}) {
            assertEquals("steamadvantage:" + name, CanonicalSteamEnchantments.canonicalId("steamadvantage:" + name));
            assertThrows(IllegalArgumentException.class, () -> CanonicalSteamEnchantments.canonicalId("basesteam:" + name));
        }
    }

    @Test public void onlyTheExactCanonicalOwnedHydroturbineCounts() {
        assertTrue(ElectricOperationalCases.hydroturbineIdentity("baseelectric", "baseelectric.Hydroturbine", "Hydroturbine"));
        assertFalse(ElectricOperationalCases.hydroturbineIdentity("other", "baseelectric.Hydroturbine", "Hydroturbine"));
        assertFalse(ElectricOperationalCases.hydroturbineIdentity("baseelectric", "baseelectric.Other", "Hydroturbine"));
        assertFalse(ElectricOperationalCases.hydroturbineIdentity("baseelectric", "baseelectric.baseelectric.Hydroturbine", "Hydroturbine"));
        assertFalse(ElectricOperationalCases.hydroturbineIdentity("baseelectric", "electricadvantage.Hydroturbine", "Hydroturbine"));
        assertFalse(ElectricOperationalCases.hydroturbineIdentity("baseelectric", "baseelectric.Hydroturbine", "Other"));
    }
}
