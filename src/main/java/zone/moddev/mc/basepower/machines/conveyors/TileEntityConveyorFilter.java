package zone.moddev.mc.basepower.machines.conveyors;

import zone.moddev.mc.basepower.util.InventoryWrapper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import zone.moddev.mc.basepower.PowerAdvantage;


public abstract class TileEntityConveyorFilter extends TileEntityConveyor {

	protected TileEntityConveyorFilter(BlockEntityType<?> type, BlockPos pos, BlockState state, int slots) {
		super(type, pos, state, slots);
	}

	public abstract boolean matchesFilter(ItemStack item);

	@Override
	public void tick() {
		Level w = getLevel();
		if (w.isClientSide()) return;
		if (transferCooldown > 0) transferCooldown--;
		if (transferCooldown == 0) {
			// do tick update
			Direction dir = this.getFacing();
			if (getInventory()[0] == null) {
				// not holding item, get item
				Direction myDir = dir.getOpposite();
				Direction theirDir = dir;
				BlockPos targetPos = getBlockPos().relative(myDir);
				BlockEntity target = w.getBlockEntity(targetPos);
				if (target != null) {
					if (target instanceof Container) {
						WorldlyContainer them = InventoryWrapper.wrap(HopperBlockEntity.getContainerAt(getLevel(), targetPos));
						if (transferItem(them, theirDir, this, myDir)) {
							this.setChanged();
						}
					}
				}
			} else {
				Direction myDir = dir;
				Direction theirDir = dir.getOpposite();
				BlockPos targetPos = getBlockPos().relative(myDir);
				BlockEntity target = w.getBlockEntity(targetPos);
				BlockEntity dropTarget = w.getBlockEntity(getBlockPos().relative(Direction.DOWN));
				if (matchesFilter(this.getInventory()[0])) {
					if (isValidItemFor(getInventory()[0], dropTarget, Direction.UP)) {
						dropItem(dropTarget);
						return;
					}
				} else if (target instanceof Container) {
					WorldlyContainer them = InventoryWrapper.wrap(HopperBlockEntity.getContainerAt(getLevel(), targetPos));
					if (transferItem(this, myDir, them, theirDir)) {
						this.setChanged();
					}
				}
			}

			transferCooldown = transferInvterval;
		}
	}

	protected boolean isValidItemFor(ItemStack item, BlockEntity target, Direction side) {
		if (item == null) return false;
		if (target instanceof Container) {
			BlockPos targetPos = target.getBlockPos();
			WorldlyContainer dt = InventoryWrapper.wrap(HopperBlockEntity.getContainerAt(getLevel(), targetPos));
			int[] slots = dt.getSlotsForFace(side);
			for (int i = 0; i < slots.length; i++) {
				int slot = slots[i];
				if (dt.canPlaceItemThroughFace(slot, item, side)) {
					return true;
				}
			}
			return false;
		} else {
			return true;
		}
	}

	private void dropItem(BlockEntity target) {
		Level w = getLevel();
		BlockState bs = w.getBlockState(getBlockPos().below());
		if (target == null && !bs.isSolid()) {
			// drop item in the air
			ItemEntity ie = new ItemEntity(w, getBlockPos().getX() + 0.5, getBlockPos().getY() - 0.5, getBlockPos().getZ() + 0.5, getInventory()[0]);
			ie.setDeltaMovement(0, 0, 0);
			w .addFreshEntity(ie);
			getInventory()[0] = null;
			transferCooldown = transferInvterval;
			this.setChanged();
		} else if (target instanceof Container) {
			// add item to inventory
			Direction myDir = Direction.DOWN;
			Direction theirDir = Direction.UP;
			BlockPos targetPos = target.getBlockPos();
			WorldlyContainer targetInv = InventoryWrapper.wrap(HopperBlockEntity.getContainerAt(getLevel(), targetPos));
			if (transferItem(this, myDir, targetInv, theirDir)) {
				transferCooldown = transferInvterval;
				this.setChanged();
			}
		}
	}

	/**
	 * Gets the direction that the blockstate is facing
	 *
	 * @return an EnumfFacing
	 */
	@Override
	public Direction getFacing() {
		BlockState state = getLevel().getBlockState(getBlockPos());
		if (state.hasProperty(BlockConveyorFilter.FACING)) {
			return state.getValue(BlockConveyorFilter.FACING);
		} else {
			PowerAdvantage.LOGGER.warn("{}.getFacing(): blockstate at {} does not have BlockConveyorFilter.FACING", this.getClass().getName(), getBlockPos());
			return Direction.NORTH;
		}
	}

	/**
	 * Determines whether another block (such as a Hopper) is allowed to pull an
	 * item from this block out through a given face
	 *
	 * @param slot       The inventory slot (index) of the item in question
	 * @param targetItem The item to be pulled
	 * @param side       The side of the block through which to pull the item
	 * @return true if the item is allowed to be pulled, false otherwise
	 */
	@Override
	public boolean canTakeItemThroughFace(final int slot, final ItemStack targetItem, final Direction side) {
		boolean filterMatch = matchesFilter(targetItem);
		if (slot != 0) return false;
		if (side == Direction.DOWN) {
			return filterMatch;
		} else {
			return (!filterMatch) && super.canTakeItemThroughFace(slot, targetItem, side);
		}
	}
}
