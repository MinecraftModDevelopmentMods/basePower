package zone.moddev.mc.advantageworks;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;

@Mod(AdvantageWorksMod.MODID)
public final class AdvantageWorksMod {
    public static final String MODID = "advantageworkstest";
    public static final String VERSION = "1.1.5.121111";

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
        NativeOilBucketProbe.register();
        Native117Readiness.register();
        NativeHarvestProbe.register();
        MachineOperationalProbe.register();
        RedstoneReloadProbe.register();
        WorksController.getInstance().loadDefinition();
        RegisterCommandsEvent.BUS.addListener(this::serverStarting);
    }

    private void serverStarting(RegisterCommandsEvent event) {
        AdvantageWorksCommand.register(event.getDispatcher());
    }
}
