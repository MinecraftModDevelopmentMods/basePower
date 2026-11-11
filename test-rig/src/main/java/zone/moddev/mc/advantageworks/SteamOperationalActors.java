package zone.moddev.mc.advantageworks;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.resources.Identifier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameType;
import net.minecraftforge.registries.ForgeRegistries;

/** Per-case entities: no shared fake-player factory identity or mutable counters. */
final class SteamOperationalActors {
    private SteamOperationalActors() {}

    static void register(List<MachineScenario> cases) {
        elevator(cases);
        musket(cases);
    }

    private static void elevator(List<MachineScenario> cases) {
        for (String mode : new String[]{"up", "down", "no-power", "scarce"}) {
            final ArmorStand[] passenger = new ArmorStand[1];
            cases.add(new MachineScenario("steam/elevator-" + mode,
                    "A real passenger moves to the physical stop, one move costs exactly 32 steam, "
                    + "a steady signal never repeats the move, and insufficient steam leaves passenger/shaft unchanged.",
                    80, c -> {
                        c.block(8, 0, 8, "basesteam:steam_elevator");
                        c.block(8, 9, 8, "minecraft:stone");
                        boolean down = mode.equals("down");
                        if (down) {
                            for (int y = 1; y <= 6; y++) platform(c, y, y == 6 ? 4 : 0);
                            CompoundTag patch = new CompoundTag(); patch.putBoolean("up", true);
                            c.seed(8, 0, 8, patch);
                        } else c.block(9, 0, 8, "minecraft:redstone_block");
                        if (!mode.equals("no-power")) c.power(8, 0, 8, "steam", mode.equals("scarce") ? 31 : 64);
                        BlockPos base = c.at(8, 0, 8);
                        ArmorStand rider = new ArmorStand(c.world, base.getX() + 0.5,
                                base.getY() + (down ? 7 : 1) + 0.00390625, base.getZ() + 0.5);
                        rider.setNoGravity(true);
                        rider.setInvulnerable(true);
                        c.require(c.world.addFreshEntity(rider), "Passenger spawned into the real server world");
                        passenger[0] = rider;
                        c.record("passenger/uuid", rider.getUUID().toString());
                        c.record("passenger/startY", rider.getY());
                    }, c -> {
                        ArmorStand rider = passenger[0];
                        c.require(rider != null && rider.isAlive(), "Passenger survives the movement test");
                        c.record("passenger/endY", rider.getY());
                        c.record("passenger/naturalTicks", rider.tickCount);
                        c.require(rider.tickCount >= 75, "Passenger received natural server entity ticks");
                        boolean movedUp = mode.equals("up"), down = mode.equals("down");
                        double destination = c.at(8, 0, 8).getY() + (movedUp ? 7 : 1) + 0.00390625;
                        c.require(Math.abs(rider.getY() - destination) < 0.001,
                                "Passenger reaches exactly the declared physical destination or stays put without power");
                        c.require(rider.fallDistance == 0, "Elevator movement resets passenger fall distance");
                        float expected = mode.equals("no-power") ? 0 : mode.equals("scarce") ? 31 : 32;
                        c.require(c.energy(8, 0, 8, "steam") == expected,
                                "Exactly one 32-steam move, or no debit below operation cost, after 80 ticks");
                        for (int y = 1; y <= 8; y++) {
                            BlockState state = c.world.getBlockState(c.at(8, y, 8));
                            c.record("shaft/" + y, state.toString());
                            if (movedUp && y <= 6) {
                                c.require("basesteam:platform".equals(String.valueOf(net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(state.getBlock()))),
                                        "Raised shaft contains physical platform segment " + y);
                                IntegerProperty height = (IntegerProperty) state.getBlock().getStateDefinition().getProperty("height");
                                c.require(height != null && state.getValue(height) == (y == 6 ? 4 : 0),
                                        "Raised shaft/top collision height is preserved at " + y);
                            } else c.require(state.isAir(), "Shaft is clear at " + y + " after " + mode);
                        }
                        c.require(c.world.getBlockState(c.at(8, 9, 8)).getBlock() == net.minecraft.world.level.block.Blocks.STONE,
                                "Elevator preserves the real upper stop");
                        c.require(c.nbt(8, 0, 8).getBoolean("up").orElseThrow() == movedUp,
                                "Saved latch agrees with physical movement, not just an NBT flag");
                    }));
        }
    }

    private static void platform(MachineContext c, int y, int height) {
        c.block(8, y, 8, "basesteam:platform");
        BlockState state = c.world.getBlockState(c.at(8, y, 8));
        IntegerProperty property = (IntegerProperty) state.getBlock().getStateDefinition().getProperty("height");
        if (property == null) throw new IllegalStateException("Missing native platform height");
        c.world.setBlockAndUpdate(c.at(8, y, 8), state.setValue(property, height));
    }

    private static final class Shot {
        WorksOfflinePlayer player;
        Cow target;
        ItemStack weapon;
        float expectedDamage;
        int expectedAmmo;
        ItemStack unladenWeapon;
        List<ItemStack> unladenInventory;
    }

    private static void musket(List<MachineScenario> cases) {
        for (String mode : new String[]{"last-cartridge", "no-ammo", "powderless", "infinity"}) {
            Shot shot = new Shot();
            cases.add(new MachineScenario("steam/musket-" + mode,
                    "Native fake-player use/release interactions prove rapid reload, ammunition policy, "
                    + "last-cartridge firing, configured target damage and one durability debit. "
                    + "The fake player is not manually ticked; this does not claim timed-reload coverage.",
                    40, c -> {
                        shootingRoom(c);
                        BlockPos muzzle = c.at(8, 0, 8);
                        UUID id = UUID.nameUUIDFromBytes(("SteamOperational/" + mode + "/" + muzzle)
                                .getBytes(StandardCharsets.UTF_8));
                        shot.player = new WorksOfflinePlayer(c.world, new GameProfile(id, "SteamOperational"));
                        shot.player.connection = new ServerGamePacketListenerImpl(c.world.getServer(),
                                new Connection(PacketFlow.SERVERBOUND), shot.player, WorksOfflinePlayer.cookie(shot.player)) {
                            @Override public void send(Packet<?> packet) {}
                        };
                        shot.player.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
                        shot.player.snapTo(muzzle.getX() + 0.5, muzzle.getY(), muzzle.getZ() + 0.5, 0, 0);
                        Item weapon = ForgeRegistries.ITEMS.getValue(Identifier.parse("basesteam:musket"));
                        c.require(weapon != null && weapon != net.minecraft.world.item.Items.AIR, "Native musket is registered");
                        shot.weapon = new ItemStack(weapon);
                        shot.player.setItemInHand(InteractionHand.MAIN_HAND, shot.weapon);
                        if (!mode.equals("no-ammo")) enchant(c, shot.weapon, "basesteam:rapid_reload");
                        if (mode.equals("powderless")) enchant(c, shot.weapon, "basesteam:powderless");
                        if (mode.equals("infinity")) enchant(c, shot.weapon, "minecraft:infinity");
                        if (mode.equals("last-cartridge") || mode.equals("infinity")) {
                            Item cartridge = ForgeRegistries.ITEMS.getValue(Identifier.parse("basesteam:blackpowder_cartridge"));
                            c.require(cartridge != null && cartridge != net.minecraft.world.item.Items.AIR, "Cartridge is registered");
                            shot.player.getInventory().setItem(1, new ItemStack(cartridge));
                        }
                        shot.expectedAmmo = mode.equals("infinity") ? 1 : 0;
                        shot.target = EntityType.COW.create(c.world, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
                        c.require(shot.target != null, "Native living target created");
                        shot.target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
                        shot.target.setHealth(1000);
                        shot.target.snapTo(muzzle.getX() + 0.5, muzzle.getY() + 0.5, muzzle.getZ() + 4.5, 0, 0);
                        shot.target.setNoAi(true); shot.target.setNoGravity(true); shot.target.setPersistenceRequired();
                        c.require(c.world.addFreshEntity(shot.target), "Living target added to the real server world");
                        c.record("target/uuid", shot.target.getUUID().toString());
                        c.record("target/initialHealth", shot.target.getHealth());
                        if (mode.equals("no-ammo")) {
                            shot.unladenWeapon = shot.weapon.copy();
                            shot.unladenInventory = new ArrayList<>();
                            for (int slot = 0; slot < shot.player.getInventory().getContainerSize(); slot++)
                                shot.unladenInventory.add(shot.player.getInventory().getItem(slot).copy());
                            c.require(!WorksItemData.read(shot.weapon).contains("loaded"),
                                    "Fresh native musket has no fabricated chamber state");
                        }
                        InteractionResult reload = weapon.use(c.world, shot.player, InteractionHand.MAIN_HAND);
                        c.record("musket/reload-result", reload.toString());
                        if (mode.equals("no-ammo")) {
                            c.require(reload == InteractionResult.FAIL, "Unloaded musket rejects use without ammunition");
                            c.require(rejectedUnladenUse(reload, shot.player.isUsingItem(), shot.unladenWeapon, shot.weapon),
                                    "Rejected native use neither starts reloading nor changes any weapon component");
                            c.require(sameInventory(shot.player.getInventory(), shot.unladenInventory),
                                    "Rejected native use preserves every inventory stack and ammunition count");
                            shot.expectedDamage = 0;
                        } else {
                            c.require(reload == InteractionResult.SUCCESS, "Rapid-reload interaction succeeds");
                            c.require(WorksItemData.read(shot.weapon).getByte("loaded").orElseThrow() == 1,
                                    "Reload interaction loads the chamber before the shot");
                            c.record("musket/ammo-after-reload", shot.player.getInventory().getItem(1).getCount());
                            c.require(shot.player.getInventory().getItem(1).getCount() == shot.expectedAmmo,
                                    "Reload consumes exactly one cartridge unless an ammunition-saving enchantment applies");
                            InteractionResult aim = weapon.use(c.world, shot.player, InteractionHand.MAIN_HAND);
                            c.record("musket/aim-result", aim.toString());
                            c.require(aim == InteractionResult.SUCCESS, "Loaded musket can aim even without a spare cartridge");
                            c.require(shot.player.isUsingItem(), "Native player enters the use lifecycle");
                            shot.player.releaseUsingItem();
                            shot.expectedDamage = shotDamage();
                        }
                    }, c -> {
                        if (mode.equals("no-ammo")) {
                            InteractionResult retry = shot.weapon.getItem().use(c.world, shot.player, InteractionHand.MAIN_HAND);
                            c.record("musket/rejected-retry-result", retry.toString());
                            c.require(rejectedUnladenUse(retry, shot.player.isUsingItem(), shot.unladenWeapon, shot.weapon),
                                    "Unladen native use still fails after server ticks with weapon and absent chamber state unchanged");
                            c.require(sameInventory(shot.player.getInventory(), shot.unladenInventory),
                                    "Repeated rejected use preserves the complete inventory and ammunition");
                        }
                        c.record("target/finalHealth", shot.target.getHealth());
                        c.record("target/naturalTicks", shot.target.tickCount);
                        c.record("musket/durabilityDebit", shot.weapon.getDamageValue());
                        c.record("musket/finalAmmo", shot.player.getInventory().getItem(1).getCount());
                        c.require(shot.target.isAlive() && shot.target.tickCount >= 35,
                                "Real target remains available after natural server ticks");
                        c.require(Math.abs(shot.target.getHealth() - (1000 - shot.expectedDamage)) < 0.001F,
                                "Target damage is exactly the configured shot damage, or zero for rejected use");
                        c.require(shot.weapon.getDamageValue() == (mode.equals("no-ammo") ? 0 : 1),
                                "Only a real shot debits one musket durability");
                        c.require(shot.player.getInventory().getItem(1).getCount() == shot.expectedAmmo,
                                "Firing does not charge ammunition a second time");
                        if (!mode.equals("no-ammo"))
                            c.require(WorksItemData.read(shot.weapon).getByte("loaded").orElseThrow() == 0,
                                    "The fired chamber has an explicit native empty state");
                        c.require(!shot.player.isCreative(), "All ammunition and durability measurements use survival mode");
                    }));
        }
    }

    static boolean rejectedUnladenUse(InteractionResult result, boolean usingItem, ItemStack before, ItemStack after) {
        return result == InteractionResult.FAIL && !usingItem
                && !WorksItemData.read(before).contains("loaded") && !WorksItemData.read(after).contains("loaded")
                && ItemStack.matches(before, after);
    }

    static boolean sameInventory(net.minecraft.world.Container inventory, List<ItemStack> before) {
        if (inventory.getContainerSize() != before.size()) return false;
        for (int slot = 0; slot < before.size(); slot++)
            if (!ItemStack.matches(before.get(slot), inventory.getItem(slot))) return false;
        return true;
    }

    private static float shotDamage() {
        try {
            float damage = Class.forName("zone.moddev.mc.basesteam.SteamAdvantage", false,
                    SteamOperationalActors.class.getClassLoader()).getDeclaredField("MUSKET_DAMAGE").getFloat(null);
            if (!Float.isFinite(damage) || damage <= 0 || damage >= 1000) throw new IllegalStateException("Unsupported configured musket damage");
            return damage;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Native musket damage contract unavailable", error);
        }
    }

    private static void enchant(MachineContext c, ItemStack weapon, String id) {
        var enchantment = c.world.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT)
                .getOrThrow(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ENCHANTMENT, Identifier.parse(id)));
        weapon.enchant(enchantment, 1);
    }

    private static void shootingRoom(MachineContext c) {
        for (int x = 6; x <= 10; x++) for (int y = 0; y <= 4; y++) for (int z = 6; z <= 16; z++) {
            if (x == 6 || x == 10 || y == 4 || z == 6 || z == 16) c.block(x, y, z, "minecraft:stone");
        }
    }
}
