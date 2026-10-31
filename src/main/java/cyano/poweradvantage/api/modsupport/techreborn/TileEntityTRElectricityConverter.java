package cyano.poweradvantage.api.modsupport.techreborn;

import cyano.poweradvantage.api.ConduitType;
import net.minecraft.world.entity.player.Player;
import zone.moddev.mc.poweradvantage.init.Entities;

/**
 * Created by Chris on 4/10/2016.
 */
public class TileEntityTRElectricityConverter extends TileEntityTRConverter {
	public TileEntityTRElectricityConverter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
		super(Entities.TR_ELECTRICITY_CONVERTER, pos, state, new ConduitType("electricity"),
				"tile.poweradvantage.converter_tr_electricity.name");
	}
	
	@Override
	public boolean stillValid(Player player) {
		return true;
	}
}
