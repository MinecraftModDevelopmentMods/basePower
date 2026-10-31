package zone.moddev.mc.poweradvantage.registry;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class MachineGuiTitleTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void defaultTitleUsesTheBlockTranslationInsteadOfAnInternalInventoryName() {
        assertEquals("block.minecraft.chest", ((TranslatableComponent) MachineGUIRegistry.menuTitle(
                new ChestBlockEntity(net.minecraft.core.BlockPos.ZERO, Blocks.CHEST.defaultBlockState()), Blocks.CHEST)).getKey());
    }

    @Test public void renamedMachinesKeepThePlayersFullName() {
        ChestBlockEntity tile = new ChestBlockEntity(net.minecraft.core.BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        tile.setCustomName(new TextComponent("John's oil tank"));
        assertEquals("John's oil tank", MachineGUIRegistry.menuTitle(tile, Blocks.CHEST).getString());
    }

    @Test public void unnamedTilesWithoutInventoryNamesStillHaveATranslatableTitle() {
        BlockEntity tile = new BlockEntity(BlockEntityType.CHEST, net.minecraft.core.BlockPos.ZERO, Blocks.CHEST.defaultBlockState()) {};
        assertEquals("block.minecraft.chest", ((TranslatableComponent) MachineGUIRegistry.menuTitle(tile, Blocks.CHEST)).getKey());
    }
}
