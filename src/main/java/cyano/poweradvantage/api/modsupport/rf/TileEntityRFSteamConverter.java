package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;


public class TileEntityRFSteamConverter extends TileEntityRFConverter {

	public TileEntityRFSteamConverter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super(zone.moddev.mc.basepower.init.Entities.RF_STEAM_CONVERTER, pos, state, new ConduitType("steam"), 1000, 1000,
				"tile.basepower.converter_rf_steam.name");
	}

}
