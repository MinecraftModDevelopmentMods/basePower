package cyano.poweradvantage.api.fluid;

import net.minecraft.resources.Identifier;

/**
 * Legacy description of a colored fluid.
 *
 * <p>Forge 1.13 no longer exposes the concrete Forge fluid type used by the
 * original API. Native fluids are registered by the implementation; this
 * descriptor remains so source integrations can carry the same metadata
 * without depending on an implementation package.</p>
 */
@Deprecated
public final class ColoredFluid {

    private final String fluidName;
    private final Identifier still;
    private final Identifier flowing;
    private final int color;

    public ColoredFluid(String fluidName, Identifier still, Identifier flowing, int tintARGB) {
        this.fluidName = fluidName;
        this.still = still;
        this.flowing = flowing;
        this.color = tintARGB;
    }

    public String getName() {
        return fluidName;
    }

    public Identifier getStill() {
        return still;
    }

    public Identifier getFlowing() {
        return flowing;
    }

    public int getColor() {
        return color;
    }
}
