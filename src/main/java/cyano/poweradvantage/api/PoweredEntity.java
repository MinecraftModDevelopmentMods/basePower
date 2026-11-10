package cyano.poweradvantage.api;

import net.minecraft.core.HolderLookup;
import zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * This class is the superclass for all machines. 
 * If you are making an add-on mod, you probably want to extend the 
 * <b>cyano.poweradvantage.api.simple.TileEntitySimplePowerConduit</b> class 
 * instead of this class.
 * <p>
 * Conceptually, a PoweredEntity is any block that has an internal energy buffer, 
 * which can be added-to or subtracted-from by other machines connected by conduits.
 * </p>
 * @author DrCyano
 *
 */
public abstract class PoweredEntity extends BlockEntity implements IPowerMachine {

	private final int powerUpdateInterval = 8;
	private int conduitRefreshDelay = 2;
    private final zone.moddev.mc.basepower.util.MachineCapabilities machineCapabilities =
            new zone.moddev.mc.basepower.util.MachineCapabilities(this);

    @Override
    public <T> net.minecraftforge.common.util.LazyOptional<T> getCapability(
            net.minecraftforge.common.capabilities.Capability<T> capability, net.minecraft.core.Direction face) {
        if (!areCapabilitiesValid()) return net.minecraftforge.common.util.LazyOptional.empty();
        if (machineCapabilities == null) return super.getCapability(capability, face);
        net.minecraftforge.common.util.LazyOptional<T> local = machineCapabilities.get(capability, face);
        return local.isPresent() ? local : super.getCapability(capability, face);
    }

    @Override public void invalidateCaps() {
        if (machineCapabilities != null) machineCapabilities.invalidate();
        super.invalidateCaps();
    }

    @Override public void reviveCaps() {
        super.reviveCaps();
        if (machineCapabilities != null) machineCapabilities.revive();
    }

    protected boolean areCapabilitiesValid() {
        return !isRemoved() && (machineCapabilities == null || machineCapabilities.isValid());
    }

    @Override public void clearRemoved() {
        super.clearRemoved();
        reviveCaps();
    }

	protected PoweredEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	/**
	 * Gets the amount of energy that can be stored in this machine.
	 *
	 * @param energyType The type of energy being polled
	 * @return Size of the energy buffer
	 */
	@Override
	public abstract float getEnergyCapacity(ConduitType energyType);

	/**
	 * Gets the amount of energy stored in this machine
	 *
	 * @param energyType The type of energy being polled
	 * @return The amount of energy in the energy buffer
	 */
	@Override
	public abstract float getEnergy(ConduitType energyType);

	/**
	 * Sets the amount of energy in the buffer
	 *
	 * @param energy The amount of energy to be added to the buffer
	 * @param type   The type of energy to be added to the buffer
	 */
	@Override
	public abstract void setEnergy(float energy, ConduitType type);

	/**
	 * Adds energy to this conductor, up to the maximum allowed energy. The
	 * amount of energy that was actually added (or subtracted) to the energy
	 * buffer is returned.
	 *
	 * @param energy The amount of energy to add (can be negative to subtract
	 *               energy).
	 * @param type   The type of energy to be added to the buffer
	 * @return The actual change to the internal energy buffer. A negative
	 * number should be returned when subtracting energy and positive when
	 * adding energy.
	 */
	@Override
	public float addEnergy(float energy, ConduitType type) {
		if (this.getEnergyCapacity(type) == 0) return 0;
		float newValue = this.getEnergy(type) + energy;
		if (newValue < 0) {
			float delta = -1 * this.getEnergy(type);
			this.setEnergy(0, type);
			if (delta != 0) markMachineStateUnsaved();
			return delta;
		} else if (newValue > this.getEnergyCapacity(type)) {
			float delta = this.getEnergyCapacity(type) - this.getEnergy(type);
			this.setEnergy(this.getEnergyCapacity(type), type);
			if (delta != 0) markMachineStateUnsaved();
			return delta;
		}
		this.setEnergy(newValue, type);
		if (energy != 0) markMachineStateUnsaved();
		return energy;
	}

	/**
	 * Subtracts energy from the energy buffer and returns the actual change to
	 * the energy buffer (which may not be as much as requested). This method
	 * simply multiplies the input energy value by -1 and then calls the method
	 * addEnergy(...).
	 *
	 * @param energy The amount of energy to subtract.
	 * @param type   The type of energy to be subtracted to the buffer
	 * @return The actual change to the internal energy buffer (a negative number)
	 */
	@Override
	public float subtractEnergy(float energy, ConduitType type) {
		return addEnergy(-1 * energy, type);
	}

	/**
	 * Tells Minecraft to synchronize this tile entity
	 */
	protected final void sync() {
		if (this.level == null || this.level.isClientSide) return;
		this.setChanged();
        BlockState state = this.level.getBlockState(getBlockPos());
        this.level.sendBlockUpdated(getBlockPos(), state, state, 2);
	}

	/**
	 * Override to keep the tile entity from being deleted each time the blockstate is updated
	 *
	 * @param world    world instance
	 * @param pos      coordinate
	 * @param oldState State before change
	 * @param newSate  State after change
	 * @return true if this TileEntity should be invalidated (deleted and recreated), false otherwise
	 */
	public boolean shouldRefresh(Level world, BlockPos pos, BlockState oldState, BlockState newSate) {
		return oldState.getBlock() != newSate.getBlock();
	}

	protected final Level powerAdvantageWorld() {
		return super.getLevel();
	}

	protected final BlockPos powerAdvantagePos() {
		return super.getBlockPos();
	}

	@Override
	public void onLoad() {
		super.onLoad();
		this.conduitRefreshDelay = 2;
		if (this.level != null && !this.level.isClientSide) {
			ConduitRegistry.getInstance().conduitBlockLoadedEvent(this.level, this.getTypes());
		}
	}

	/**
	 * Sends this tile's current update packet to one player. GUI blocks use this
	 * when opening a machine so indicators never depend on a previous energy
	 * change having triggered a broadcast packet.
	 *
	 * @param player player receiving the current machine state
	 */
	public final void syncPowerAdvantageDataTo(ServerPlayer player) {
		this.setChanged();
		Packet<?> packet = this.getUpdatePacket();
		if (packet != null) {
			player.connection .send(packet);
		}
	}

	/**
	 * Method net.minecraft.server.gui.IUpdatePlayerListBox.update() is invoked
	 * to do tick updates
	 */
	public final void tick() {
		// this method was moved from TileEntity to IUpdatePlayerListBox
		if (this.isServer()) {
			this.refreshConduitNetworkAfterLoad();
		}
		this.tickUpdate(this.isServer());
		if (this.isServer() && ((this.getLevel().getGameTime() + this.getTickOffset()) % powerUpdateInterval == 0)) {
			this.powerUpdate();
		}
		markMachineStateUnsaved();
	}

	private void markMachineStateUnsaved() {
		if (!(this.level instanceof net.minecraft.server.level.ServerLevel) || isRemoved()) return;
		BlockPos pos = getBlockPos();
		net.minecraft.world.level.chunk.LevelChunk chunk = ((net.minecraft.server.level.ServerLevel) this.level)
				.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		// Timers and externally supplied energy can change without a GUI sync.
		// Mark the loaded chunk only; do not load neighbours or notify comparators.
		if (chunk != null) chunk.setUnsaved(true);
	}

	private void refreshConduitNetworkAfterLoad() {
		if (this.conduitRefreshDelay < 0) return;
		if (this.conduitRefreshDelay-- > 0) return;
		ConduitRegistry.getInstance().conduitBlockLoadedEvent(this.level, this.getTypes());
	}

	/**
	 * Called every tick to update. Note that tickUpdate happens before
	 * powerUpdate().
	 *
	 * @param isServerWorld Will be true if the code is executing server-side,
	 *                      and false if executing client-side
	 */
	public abstract void tickUpdate(boolean isServerWorld);

	/**
	 * Updates the power status. This method is called every few ticks and is
	 * only called on the server world.
	 */
	public abstract void powerUpdate();

	/**
	 * Returns false if this code is executing on the client and true if this
	 * code is executing on the server
	 *
	 * @return true if on server world, false otherwise
	 */
	public boolean isServer() {
		return !this.getLevel().isClientSide;
	}

	/**
	 * Returns true if this code is executing on the client and false if this
	 * code is executing on the server
	 *
	 * @return false if on server world, true if on client
	 */
	public boolean isClient() {
		return this.getLevel().isClientSide;
	}

	/**
	 * Returns a number that is used to spread the power updates in a chunk
	 * across multiple ticks. Also keeps adjacent conductors from updating in
	 * the same tick.
	 *
	 * @return
	 */
	private final int getTickOffset() {
		BlockPos coord = this.getBlockPos();
		int x = coord.getX();
		int y = coord.getY();
		int z = coord.getZ();
		return ((z & 1) << 2) | ((x & 1) << 1) | ((y & 1));
	}

	/**
	 * Reads data from NBT, which came from either a saved chunk or a network
	 * packet.
	 */
	@Override
	protected void loadAdditional(final CompoundTag tagRoot, HolderLookup.Provider registries) {
		super.loadAdditional(tagRoot, registries);
		this.conduitRefreshDelay = 2;
		if (tagRoot.contains("Energy", 11)) {
			int[] data = tagRoot.getIntArray("Energy");
			for (int i = 0; i < data.length; i++) {
				if (i >= this.getTypes().length) break;
				this.setEnergy(Float.intBitsToFloat(data[i]), this.getTypes()[i]);
			}
		}
	}

	/**
	 * Saves the state of this entity to an NBT for saving or synching across
	 * the network.
	 */
	@Override
	protected void saveAdditional(final CompoundTag tagRoot, HolderLookup.Provider registries) {
		super.saveAdditional(tagRoot, registries);
		int[] data = new int[this.getTypes().length];
		for (int i = 0; i < data.length; i++) {
			data[i] = Float.floatToIntBits(this.getEnergy(this.getTypes()[i]));
		}
		tagRoot.putIntArray("Energy", data);
	}

	private HolderLookup.Provider serializationRegistries() {
		return getLevel() == null
				? net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY)
				: getLevel().registryAccess();
	}

	/** @deprecated Prefer the registry-aware Minecraft save methods. */
	@Deprecated public CompoundTag save(CompoundTag tag) {
		return tag.merge(saveWithFullMetadata(serializationRegistries()));
	}

	/** @deprecated Prefer {@link #loadWithComponents}. */
	@Deprecated public void load(CompoundTag tag) { loadWithComponents(tag, serializationRegistries()); }

	/** @deprecated Prefer {@link #loadWithComponents}. */
	@Deprecated public void read(CompoundTag tag) { load(tag); }

	/** @deprecated Prefer the registry-aware Minecraft save methods. */
	@Deprecated public CompoundTag write(CompoundTag tag) { return save(tag); }


	/**
	 * Collects all requests for energy from power sinks connected to this tile entity
	 *
	 * @param powerType The type of power available to donate to the sinks
	 * @return Returns a list of all connected entities that want energy, in order from highest
	 * priority to lowest (energy storage will have lower priority than machines).
	 */
	protected List<PowerRequest> getRequestsForPower(ConduitType powerType) {
		return ConduitRegistry.getInstance().getRequestsForPower(getLevel(), getBlockPos(), powerType, powerType);
	}

	/**
	 * Specify how much energy this power sink wants from a power generator. If this tile entity is
	 * not a sink, then simply return PowerRequest.REQUEST_NOTHING
	 *
	 * @param type The type of energy available upon request
	 * @return A PowerRequest instance indicated how much power you'd like to get
	 */
	@Override
	public abstract PowerRequest getPowerRequest(ConduitType type);

	/**
	 * This method is invoked when the block is placed using an item that has
	 * been renamed. Implementations can carry the name over to the placed
	 * block, but that feature is optional.
	 *
	 * @param newName The name of the item that was placed.
	 */
	public void setCustomInventoryName(String newName) {
		// optional method
	}
}
