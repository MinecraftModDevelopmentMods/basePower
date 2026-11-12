package zone.moddev.mc.basepower.util;

import net.minecraft.advancements.predicates.ItemPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.LockCode;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class InventoryLocksTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void ordinaryInventoryNeedsNoVanillaLockAccess() {
        assertFalse(InventoryLocks.isLocked(new SimpleContainer(1)));
        assertFalse(InventoryLocks.isLocked(null));
    }

    @Test public void unlockedVanillaChestIsAccessible() {
        assertFalse(InventoryLocks.isLocked(chest(false)));
    }

    @Test public void lockedVanillaChestAndItsWrapperStayLocked() {
        ChestBlockEntity chest = chest(true);
        assertTrue(chest.isLocked());
        assertTrue(InventoryLocks.isLocked(chest));
        assertTrue(InventoryLocks.isLocked(InventoryWrapper.wrap(chest)));
    }

    @Test public void bothUnlockedDoubleChestHalvesAreAccessible() {
        assertFalse(InventoryLocks.isLocked(new CompoundContainer(chest(false), chest(false))));
    }

    @Test public void eitherLockedDoubleChestHalfBlocksAutomation() {
        assertTrue(InventoryLocks.isLocked(new CompoundContainer(chest(true), chest(false))));
        assertTrue(InventoryLocks.isLocked(new CompoundContainer(chest(false), chest(true))));
    }

    @Test public void nestedContainersCannotHideALockedChest() {
        CompoundContainer nested = new CompoundContainer(new SimpleContainer(1),
                new CompoundContainer(chest(false), chest(true)));
        assertTrue(InventoryLocks.isLocked(nested));
        assertTrue(InventoryLocks.isLocked(InventoryWrapper.wrap(nested)));
    }

    private static ChestBlockEntity chest(boolean locked) {
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        if (locked) {
            LockCode code = new LockCode(ItemPredicate.Builder.item()
                    .of(BuiltInRegistries.ITEM, Items.TRIPWIRE_HOOK).build());
            chest.applyComponents(DataComponentMap.EMPTY,
                    DataComponentPatch.builder().set(DataComponents.LOCK, code).build());
        }
        return chest;
    }
}
