package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityRFElectricityConverter extends TileEntityRFConverter {

	public TileEntityRFElectricityConverter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super(Entities.RF_ELECTRICITY_CONVERTER, pos, state, new ConduitType("electricity"), 8000, 1000,
				"tile.basepower.converter_rf_electricity.name");
	}

}
