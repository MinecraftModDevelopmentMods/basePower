package com.mcmoddev.poweradvantage.conduitnetwork;

import java.util.List;

import cyano.poweradvantage.api.ConduitType;
import cyano.poweradvantage.api.PowerRequest;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Compatibility facade for the reflective IronAgeFurniture 1.10 integration.
 *
 * @deprecated Use {@code cyano.poweradvantage.api.fluid.FluidNetworkApi}.
 */
@Deprecated
public final class ConduitRegistry {
	private static final ConduitRegistry INSTANCE = new ConduitRegistry();

	private ConduitRegistry() {
	}

	public static ConduitRegistry getInstance() {
		return INSTANCE;
	}

	public List<PowerRequest> getRequestsForPower(World world, BlockPos position, ConduitType conduitType) {
		return delegate().getRequestsForPower(world, position, conduitType);
	}

	public List<PowerRequest> getRequestsForPower(World world, BlockPos position, ConduitType conduitType,
			ConduitType energyType) {
		return delegate().getRequestsForPower(world, position, conduitType, energyType);
	}

	public static float transmitPowerToConsumers(float availableEnergy, ConduitType powerType,
			byte minimumPriority, TileEntity provider) {
		return zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry.transmitPowerToConsumers(
			availableEnergy, powerType, minimumPriority, provider);
	}

	public static float transmitPowerToConsumers(float availableEnergy, ConduitType networkType,
			ConduitType powerType, byte minimumPriority, World world, BlockPos sourcePosition,
			Object provider) {
		return zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry.transmitPowerToConsumers(
			availableEnergy, networkType, powerType, minimumPriority, world, sourcePosition, provider);
	}

	public void conduitBlockPlacedEvent(World world, int dimension, BlockPos position, ConduitType type) {
		delegate().conduitBlockPlacedEvent(world, dimension, position, type);
	}

	public void conduitBlockPlacedEvent(World world, int dimension, BlockPos position, ConduitType... types) {
		delegate().conduitBlockPlacedEvent(world, dimension, position, types);
	}

	public void conduitBlockLoadedEvent(World world, int dimension, ConduitType... types) {
		delegate().conduitBlockLoadedEvent(world, dimension, types);
	}

	public void conduitBlockRemovedEvent(World world, int dimension, BlockPos position, ConduitType type) {
		delegate().conduitBlockRemovedEvent(world, dimension, position, type);
	}

	public void conduitBlockRemovedEvent(World world, int dimension, BlockPos position, ConduitType... types) {
		delegate().conduitBlockRemovedEvent(world, dimension, position, types);
	}

	private zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry delegate() {
		return zone.moddev.mc.basepower.conduitnetwork.ConduitRegistry.getInstance();
	}
}
