package zone.moddev.mc.advantageworks;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(AdvantageWorksMod.MODID)
public final class AdvantageWorksMod {
    public static final String MODID = "advantageworkstest";
    public static final String VERSION = "1.1.6.121012";

    public AdvantageWorksMod() {
        MigrationProbe.register();
        ConnectedProbe.register();
        ActorProbe.register();
        DimensionEntityProbe.register();
        Native117StoppedEntitiesSnapshot.register();
        LifecycleProbe.register();
        RecipeUnlockProbe.register();
        ProgressionProbe.register();
        NativeInteropProbe.register();
        CrushingInteropProbe.register();
        NativeOilBucketProbe.register();
        Native117Readiness.register();
        NativeHarvestProbe.register();
        MachineOperationalProbe.register();
        RedstoneReloadProbe.register();
        WorksController.getInstance().loadDefinition();
        NeoForge.EVENT_BUS.addListener(this::serverStarting);
    }

    private void serverStarting(RegisterCommandsEvent event) {
        AdvantageWorksCommand.register(event.getDispatcher());
    }
}
