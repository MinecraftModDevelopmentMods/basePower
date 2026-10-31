package zone.moddev.mc.poweradvantage.registry;

import cyano.poweradvantage.api.GUIBlock;
import cyano.poweradvantage.api.simple.SimpleMachineGUI;
import net.minecraft.world.level.block.Block;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.MenuProvider;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.extensions.IForgeContainerType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fmllegacy.network.NetworkHooks;
import zone.moddev.mc.poweradvantage.PowerAdvantage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Legacy GUI definitions backed by Forge's registered container and screen factories. */
@Mod.EventBusSubscriber(modid = PowerAdvantage.MODID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class MachineGUIRegistry {
    private static final MachineGUIRegistry INSTANCE = new MachineGUIRegistry();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private static final Map<Integer, ITileEntityGUI> DEFINITIONS = new ConcurrentHashMap<>();
    public static MenuType<SimpleMachineGUI.Container> TYPE;

    private MachineGUIRegistry() {}

    public static MachineGUIRegistry getInstance() { return INSTANCE; }

    public static int addGUI(ITileEntityGUI gui) {
        int id = NEXT_ID.getAndIncrement();
        DEFINITIONS.put(id, gui);
        return id;
    }

    public void removeGUI(int id) { DEFINITIONS.remove(id); }

    @SubscribeEvent
    public static void registerTypes(RegistryEvent.Register<MenuType<?>> event) {
        TYPE = IForgeContainerType.create(MachineGUIRegistry::createClientContainer);
        TYPE.setRegistryName(PowerAdvantage.MODID, "machine");
        event.getRegistry().register(TYPE);
    }

    private static SimpleMachineGUI.Container createClientContainer(
            int windowId, Inventory inventory, FriendlyByteBuf data) {
        BlockPos pos = data.readBlockPos();
        Level world = inventory.player.level;
        BlockEntity tile = world.getBlockEntity(pos);
        Block block = world.getBlockState(pos).getBlock();
        if (!(block instanceof GUIBlock) || !(tile instanceof Container)) {
            throw new IllegalStateException("Missing machine for container at " + pos);
        }
        // Resolve the local block's definition; numeric registration order is not a network identity.
        return definition(((GUIBlock) block).getGuiID()).createMenu(windowId, inventory, (Container) tile);
    }

    private static SimpleMachineGUI definition(int id) {
        ITileEntityGUI gui = DEFINITIONS.get(id);
        if (!(gui instanceof SimpleMachineGUI)) {
            throw new IllegalStateException("No native machine GUI registered for " + id);
        }
        return (SimpleMachineGUI) gui;
    }

    @OnlyIn(Dist.CLIENT)
    public static void registerScreens() {
        MenuScreens.<SimpleMachineGUI.Container, SimpleMachineGUI.GUIContainer>register(TYPE,
                (container, inventory, title) -> container.createScreen(inventory, title));
    }

    public static void open(ServerPlayer player, Level world, BlockPos pos, int id) {
        BlockEntity tile = world.getBlockEntity(pos);
        if (world.isClientSide || player.level != world || !(tile instanceof Container)) return;
        SimpleMachineGUI gui = definition(id);
        NetworkHooks.openGui(player, new MenuProvider() {
            @Override public AbstractContainerMenu createMenu(int windowId, Inventory inventory, Player owner) {
                return gui.createMenu(windowId, inventory, (Container) tile);
            }
            @Override public Component getDisplayName() {
                return menuTitle(tile, world.getBlockState(pos).getBlock());
            }
        }, pos);
    }

    static Component menuTitle(BlockEntity tile, Block block) {
        if (tile instanceof net.minecraft.world.Nameable) {
            Component customName = ((net.minecraft.world.Nameable) tile).getCustomName();
            if (customName != null) return customName;
        }
        return new TranslatableComponent(block.getDescriptionId());
    }

    public Object getServerGuiElement(int id, Player player, Level world, int x, int y, int z) {
        ITileEntityGUI gui = DEFINITIONS.get(id);
        BlockEntity tile = world.getBlockEntity(new BlockPos(x, y, z));
        return gui == null || tile == null ? null : gui.getContainer(tile, player);
    }

    @OnlyIn(Dist.CLIENT)
    public Object getClientGuiElement(int id, Player player, Level world, int x, int y, int z) {
        ITileEntityGUI gui = DEFINITIONS.get(id);
        BlockEntity tile = world.getBlockEntity(new BlockPos(x, y, z));
        return gui == null || tile == null ? null : gui.getContainerGUI(tile, player);
    }
}
