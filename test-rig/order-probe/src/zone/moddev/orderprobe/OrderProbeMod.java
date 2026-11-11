package zone.moddev.orderprobe;

@net.minecraftforge.fml.common.Mod("advantageorderprobe")
public final class OrderProbeMod {
    public OrderProbeMod() {
        try {
            // Old fully-generated worlds need not use worldgen. Load, but do not
            // initialize or invoke, the existing target so its weaving is auditable.
            Class.forName("net.minecraft.server.level.WorldGenRegion", false, getClass().getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new AssertionError("Native order audit target missing", failure);
        }
    }
}
