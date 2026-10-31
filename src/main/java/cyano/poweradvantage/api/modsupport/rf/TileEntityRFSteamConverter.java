package cyano.poweradvantage.api.modsupport.rf;

import cyano.poweradvantage.api.ConduitType;
import zone.moddev.mc.poweradvantage.init.Entities;

public class TileEntityRFSteamConverter extends TileEntityRFConverter {

	public TileEntityRFSteamConverter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super(Entities.RF_STEAM_CONVERTER, pos, state, new ConduitType("steam"), 1000, 1000,
				"tile.poweradvantage.converter_rf_steam.name");
	}

}
