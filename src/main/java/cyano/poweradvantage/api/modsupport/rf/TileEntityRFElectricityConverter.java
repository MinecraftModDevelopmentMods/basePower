package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;


public class TileEntityRFElectricityConverter extends TileEntityRFConverter {

	public TileEntityRFElectricityConverter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super(zone.moddev.mc.basepower.init.Entities.RF_ELECTRICITY_CONVERTER, pos, state, new ConduitType("electricity"), 8000, 1000,
				"tile.basepower.converter_rf_electricity.name");
	}

}
