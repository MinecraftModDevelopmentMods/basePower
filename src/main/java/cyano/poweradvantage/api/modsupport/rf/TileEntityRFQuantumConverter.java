package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityRFQuantumConverter extends TileEntityRFConverter {

	public TileEntityRFQuantumConverter() {
		super(Entities.RF_QUANTUM_CONVERTER, new ConduitType("quantum"), 1000, 1000,
				"tile.basepower.converter_rf_quantum.name");
	}

}
