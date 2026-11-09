package zone.moddev.mc.basepower.registry;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class MachineGuiTitleTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void defaultTitleUsesTheBlockTranslationInsteadOfAnInternalInventoryName() {
        assertEquals("block.minecraft.chest", key(MachineGUIRegistry.menuTitle(
                new ChestBlockEntity(net.minecraft.core.BlockPos.ZERO, Blocks.CHEST.defaultBlockState()), Blocks.CHEST)));
    }

    @Test public void renamedMachinesKeepThePlayersFullName() {
        ChestBlockEntity tile = new ChestBlockEntity(net.minecraft.core.BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        tile.setCustomName(Component.literal("John's oil tank"));
        assertEquals("John's oil tank", MachineGUIRegistry.menuTitle(tile, Blocks.CHEST).getString());
    }

    @Test public void unnamedTilesWithoutInventoryNamesStillHaveATranslatableTitle() {
        BlockEntity tile = new BlockEntity(BlockEntityType.CHEST, net.minecraft.core.BlockPos.ZERO, Blocks.CHEST.defaultBlockState()) {};
        assertEquals("block.minecraft.chest", key(MachineGUIRegistry.menuTitle(tile, Blocks.CHEST)));
    }

    private static String key(Component title) {
        return ((TranslatableContents) title.getContents()).getKey();
    }
}
