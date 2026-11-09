package zone.moddev.mc.basepower.machines.creative;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.simple.TileEntitySimplePowerMachine;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import zone.moddev.mc.basepower.init.Entities;

public class InfiniteEnergyTileEntity extends TileEntitySimplePowerMachine {

	private final ItemStack[] inventory = new ItemStack[0];
	private final int[] dataArray = new int[0];
	private final ConduitType[] type = new ConduitType[1];

	public InfiniteEnergyTileEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state, ConduitType energyType) {
		super(Entities.INFINITE_ENERGY, pos, state, energyType, 1000f, "tile.basepower.infinite_energy_source.name");
		this.type[0] = energyType;
	}

	public InfiniteEnergyTileEntity(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super(Entities.INFINITE_ENERGY, pos, state, new ConduitType("power"), 1000f, "tile.basepower.infinite_energy_source.name");
		this.type[0] = new ConduitType("power");
	}

	public void setPowerType(ConduitType type) {
		this.type[0] = type;
	}

	@Override
	public ConduitType[] getTypes() {
		return type;
	}

	/**
	 * Determines whether this block/entity should receive energy. If this is not a sink, then it
	 * will never be given power by a power source.
	 *
	 * @param powerType Type of power
	 * @return true if this block/entity should receive energy
	 */
	@Override
	public boolean isPowerSink(ConduitType powerType) {
		return false;
	}

	/**
	 * Determines whether this block/entity can provide energy.
	 *
	 * @param powerType Type of power
	 * @return true if this block/entity can provide energy
	 */
	@Override
	public boolean isPowerSource(ConduitType powerType) {
		return true;
	}


	@Override
	protected ItemStack[] getInventory() {
		return inventory;
	}

	@Override
	public int[] getDataFieldArray() {
		return dataArray;
	}

	@Override
	public void prepareDataFieldsForSync() {
		// do nothing

	}

	@Override
	public void onDataFieldUpdate() {
		// do nothing

	}

	@Override
	public void tickUpdate(boolean isServerWorld) {
		// do nothing
	}

	@Override
	public void powerUpdate() {
		super.powerUpdate();
		this.setEnergy(this.getEnergyCapacity(this.getTypes()[0]), this.getTypes()[0]);
	}

	@Override
	protected void saveAdditional(final CompoundTag tagRoot) {
		super.saveAdditional(tagRoot);
		tagRoot.putString("InfType", this.type[0].toString());
	}

	@Override
	public void load(final CompoundTag tagRoot) {
		super.load(tagRoot);
		if (tagRoot.contains("InfType", 8)) {
			this.type[0] = new ConduitType(tagRoot.getString("InfType"));
		}
	}
}
