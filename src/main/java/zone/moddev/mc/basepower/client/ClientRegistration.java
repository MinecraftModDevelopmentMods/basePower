package zone.moddev.mc.basepower.client;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import zone.moddev.mc.basepower.registry.MachineGUIRegistry;

/** Loaded only on the physical client. */
public final class ClientRegistration {
    private ClientRegistration() { }

    public static void register(IEventBus bus) {
        bus.addListener(MachineGUIRegistry::registerScreens);
        bus.addListener((FMLClientSetupEvent event) -> event.enqueueWork(ClientOilRenderer::configureRenderLayers));
    }
}
