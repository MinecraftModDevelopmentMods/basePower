package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityRFElectricityConverter extends TileEntityRFConverter {

	public TileEntityRFElectricityConverter() {
		super(Entities.RF_ELECTRICITY_CONVERTER, new ConduitType("electricity"), 8000, 1000,
				"tile.basepower.converter_rf_electricity.name");
	}

}
