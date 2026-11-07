package zone.moddev.mc.basepower.util;

import zone.moddev.mc.basepower.PowerAdvantage;
import cyano.poweradvantage.api.ITypedConduit;
import cyano.poweradvantage.api.PowerConnectorContext;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.core.Direction;
import net.minecraftforge.energy.CapabilityEnergy;

/**
 * Collection of utility methods
 * @author DrCyano
 *
 */
public abstract class PowerHelper {

	/**
	 * Checks if a connection is valid by looking at the two blocks and asking each if they will connect to the other.
	 *
	 * @param connection The connection to check
	 * @return True if the two blocks cen send power to (or through) eachother, false otherwise
	 */
	public static boolean areConnectable(PowerConnectorContext connection) {
		if (connection.thisBlock.getBlock() instanceof ITypedConduit) {
			if (connection.otherBlock.getBlock() instanceof ITypedConduit) {
				// both blocks are Power Advantage conductors
				return ((ITypedConduit) connection.thisBlock.getBlock()).canAcceptConnection(connection)
						&& ((ITypedConduit) connection.otherBlock.getBlock()).canAcceptConnection(connection.reverse());
			} else if (connection.otherBlock.getBlock() instanceof EntityBlock
					&& PowerAdvantage.forgeEnergyConversionTable.containsKey(connection.powerType)) {
				BlockEntity tile = connection.world.getBlockEntity(connection.otherBlockPosition);
				if (tile == null) return false;
				for (Direction side : Direction.values()) {
					if (tile.getCapability(CapabilityEnergy.ENERGY, side).isPresent()) return true;
				}
			}
		}
		return false;
	}
}
