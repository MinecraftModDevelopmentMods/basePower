package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;
import zone.moddev.mc.basepower.init.Entities;

public class TileEntityRFSteamConverter extends TileEntityRFConverter {

	public TileEntityRFSteamConverter() {
		super(Entities.RF_STEAM_CONVERTER, new ConduitType("steam"), 1000, 1000,
				"tile.basepower.converter_rf_steam.name");
	}

}
