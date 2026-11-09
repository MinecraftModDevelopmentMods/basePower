package zone.moddev.mc.advantageworks;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(AdvantageWorksMod.MODID)
public final class AdvantageWorksMod {
    public static final String MODID = "advantageworkstest";
    public static final String VERSION = "1.1.2.118021";

    public AdvantageWorksMod() {
        FMLJavaModLoadingContext.get().getModEventBus()
                .addGenericListener(ForgeWorldPreset.class, Native118TestFlat::register);
        MigrationProbe.register();
        ConnectedProbe.register();
        ActorProbe.register();
        DimensionEntityProbe.register();
        Native117StoppedEntitiesSnapshot.register();
        LifecycleProbe.register();
        RecipeUnlockProbe.register();
        ProgressionProbe.register();
        NativeInteropProbe.register();
        Native117Readiness.register();
        NativeHarvestProbe.register();
        MachineOperationalProbe.register();
        RedstoneReloadProbe.register();
        WorksController.getInstance().loadDefinition();
        MinecraftForge.EVENT_BUS.addListener(this::serverStarting);
    }

    private void serverStarting(RegisterCommandsEvent event) {
        AdvantageWorksCommand.register(event.getDispatcher());
    }
}
