package zone.moddev.mc.advantageworks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.LockCode;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

public class Native12111OperationalFixturesTest {
    @BeforeClass public static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test public void nativeLockCodecInstallsAVanillaChestLockWithoutChangingItsStock() throws Exception {
        ChestBlockEntity chest = chest();
        CompoundTag patch = PowerOperationalCases.nativeChestLock(chest.getLevel().registryAccess());
        assertTrue(patch.contains("lock"));
        assertFalse(patch.contains("Lock"));
        LockCode lock = LockCode.CODEC.parse(chest.getLevel().registryAccess()
                .createSerializationContext(NbtOps.INSTANCE), patch.get("lock")).getOrThrow();
        assertTrue(lock.unlocksWith(new ItemStack(Items.STICK)));
        assertFalse(lock.unlocksWith(ItemStack.EMPTY));
        assertFalse(lock.unlocksWith(new ItemStack(Items.DIRT)));
        CompoundTag saved = WorksBlockData.save(chest);
        saved.merge(patch);
        WorksBlockData.load(chest, saved);
        assertTrue(chest.isLocked());
        assertTrue(WorksBlockData.save(chest).contains("lock"));
        assertTrue(chest.getItem(0).is(Items.DIRT));
        assertEquals(16, chest.getItem(0).getCount());
        saved.remove("lock");
        WorksBlockData.load(chest, saved);
        assertFalse(chest.isLocked());
        assertEquals(16, chest.getItem(0).getCount());
    }

    @Test public void legacyStringLockDoesNotQualifyAsALockedNativeFixture() throws Exception {
        ChestBlockEntity chest = chest();
        CompoundTag saved = WorksBlockData.save(chest);
        saved.putString("Lock", "Works key");
        WorksBlockData.load(chest, saved);
        assertFalse("The old fixture never installed a native lock", chest.isLocked());
        assertFalse(WorksBlockData.save(chest).contains("lock"));
    }

    @Test public void completedOilChargeRequiresIndependentCounterAndSavedTotalEvidence() {
        CompoundTag saved = completedBurn();
        assertTrue(SteamOperationalCases.oilChargeBurnedOut(0, 5, saved));
        assertFalse("An active burn cannot pass merely because its saved field is absent",
                SteamOperationalCases.oilChargeBurnedOut(1, 5, saved));
        assertFalse("A never-ignited charge is not a completed burn",
                SteamOperationalCases.oilChargeBurnedOut(0, 0, new CompoundTag()));
        assertFalse(SteamOperationalCases.oilChargeBurnedOut(-1, 5, saved));
        assertFalse(SteamOperationalCases.oilChargeBurnedOut(0, 4, saved));
    }

    @Test public void missingWrongTypedAndContradictoryBurnEvidenceFailClosed() {
        assertFalse(SteamOperationalCases.oilChargeBurnedOut(0, 5, new CompoundTag()));
        CompoundTag saved = new CompoundTag();
        saved.putString("BurnTimeTotal", "5");
        assertFalse(SteamOperationalCases.oilChargeBurnedOut(0, 5, saved));
        saved = completedBurn();
        saved.putInt("BurnTime", 0);
        assertFalse("The native serializer omits a zero counter", SteamOperationalCases.oilChargeBurnedOut(0, 5, saved));
        saved.putInt("BurnTime", 1);
        assertFalse(SteamOperationalCases.oilChargeBurnedOut(0, 5, saved));
    }

    @Test public void unladenRejectionRequiresFailNoUseLifecycleAndAnUnmodifiedWeapon() {
        ItemStack before = new ItemStack(Items.CROSSBOW), after = before.copy();
        assertTrue(SteamOperationalActors.rejectedUnladenUse(InteractionResult.FAIL, false, before, after));
        for (InteractionResult result : List.of(InteractionResult.PASS, InteractionResult.SUCCESS, InteractionResult.CONSUME))
            assertFalse(SteamOperationalActors.rejectedUnladenUse(result, false, before, after));
        assertFalse(SteamOperationalActors.rejectedUnladenUse(InteractionResult.FAIL, true, before, after));
        after.setDamageValue(1);
        assertFalse(SteamOperationalActors.rejectedUnladenUse(InteractionResult.FAIL, false, before, after));
        after = before.copy();
        after.setCount(2);
        assertFalse(SteamOperationalActors.rejectedUnladenUse(InteractionResult.FAIL, false, before, after));
    }

    @Test public void fabricatedEmptyChamberAndOtherComponentWritesAreNotRejectedUseWitnesses() {
        ItemStack before = new ItemStack(Items.CROSSBOW), after = before.copy();
        WorksItemData.update(after, tag -> tag.putByte("loaded", (byte)0));
        assertFalse(SteamOperationalActors.rejectedUnladenUse(InteractionResult.FAIL, false, before, after));
        assertFalse(SteamOperationalActors.rejectedUnladenUse(InteractionResult.FAIL, false, after, after.copy()));
        after = before.copy();
        WorksItemData.update(after, tag -> tag.putString("private", "changed"));
        assertFalse(SteamOperationalActors.rejectedUnladenUse(InteractionResult.FAIL, false, before, after));
    }

    @Test public void fullInventoryOracleRejectsAmmunitionDamageComponentAndSlotChanges() {
        SimpleContainer inventory = new SimpleContainer(new ItemStack(Items.CROSSBOW), new ItemStack(Items.ARROW, 3));
        List<ItemStack> before = List.of(inventory.getItem(0).copy(), inventory.getItem(1).copy());
        assertTrue(SteamOperationalActors.sameInventory(inventory, before));
        inventory.getItem(1).shrink(1);
        assertFalse(SteamOperationalActors.sameInventory(inventory, before));
        inventory.setItem(1, before.get(1).copy());
        inventory.getItem(0).setDamageValue(1);
        assertFalse(SteamOperationalActors.sameInventory(inventory, before));
        inventory.setItem(0, before.get(0).copy());
        WorksItemData.update(inventory.getItem(1), tag -> tag.putInt("private", 1));
        assertFalse(SteamOperationalActors.sameInventory(inventory, before));
        assertFalse(SteamOperationalActors.sameInventory(new SimpleContainer(1), before));
    }

    @Test public void operatingFixturesUseNativePreconditionsCountersAndPublicRejectedUse() throws Exception {
        String power = source("PowerOperationalCases.java");
        assertFalse(power.contains("putString(\"Lock\""));
        assertTrue(power.contains("LockCode.CODEC.encodeStart("));
        assertTrue(power.contains(".isLocked()"));
        assertTrue(power.contains("locked route delivers no stock"));
        assertTrue(power.contains("locked route retains every input item"));
        String boiler = source("SteamOperationalCases.java");
        assertTrue(boiler.contains("boiler.prepareDataFieldsForSync()"));
        assertTrue(boiler.contains("boiler.getDataFieldArray()"));
        assertTrue(boiler.contains("oilChargeBurnedOut(counters[2], counters[3], c.nbt(X, Y, Z))"));
        assertFalse(boiler.contains("getInt(\"BurnTime\").orElse"));
        String musket = source("SteamOperationalActors.java");
        assertTrue(musket.contains("InteractionResult reload = weapon.use("));
        assertTrue(musket.contains("InteractionResult retry = shot.weapon.getItem().use("));
        assertTrue(musket.contains("shot.player.isUsingItem()"));
        assertTrue(musket.contains("sameInventory(shot.player.getInventory(), shot.unladenInventory)"));
        assertTrue(musket.contains("getByte(\"loaded\").orElseThrow() == 0"));
        assertFalse(musket.contains("putByte(\"loaded\""));
        assertFalse(musket.contains("getByteOr(\"loaded\""));
        assertTrue(musket.contains("shot.target.getHealth() - (1000 - shot.expectedDamage)"));
        assertTrue(musket.contains("shot.weapon.getDamageValue() == (mode.equals(\"no-ammo\") ? 0 : 1)"));
    }

    private static CompoundTag completedBurn() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("BurnTimeTotal", 5);
        return saved;
    }

    private static ChestBlockEntity chest() throws Exception {
        ChestBlockEntity chest = new ChestBlockEntity(BlockPos.ZERO, Blocks.CHEST.defaultBlockState());
        chest.setItem(0, new ItemStack(Items.DIRT, 16));
        chest.setLevel(Native117EntityContextTest.world(Level.OVERWORLD));
        return chest;
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/zone/moddev/mc/advantageworks", name));
    }
}
