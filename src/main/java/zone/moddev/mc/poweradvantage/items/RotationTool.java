package zone.moddev.mc.poweradvantage.items;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

public class RotationTool extends net.minecraft.world.item.Item {

	public RotationTool(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		final Player player = context.getPlayer();
		final Level w = context.getLevel();
		final BlockPos coord = context.getClickedPos();
		final Direction facing = context.getClickedFace();
		BlockState bs = w.getBlockState(coord);
		/*  // comment this line to turn the wrench into a hacker tool
		try { // hacker info
			FMLLog.info("%s",new StringBuilder()
					.append("Hacker output:\n")
					.append("\nBlockstate = ").append(block)
					.append("\n\nBlock class dump:\n").append(superDump(block.getBlock()))
					.append("\n\nTileEntity class dump:\n")
					.append(w.getTileEntity(coord) == null ? "null" : superDump(w.getTileEntity(coord)))
			.toString());
		} catch (IllegalAccessException e) {
			FMLLog.log(Level.WARN,e,"Hacker fail!");
		}
		//*/

		BlockState rotated = bs.rotate(w, coord, Rotation.COUNTERCLOCKWISE_90);
		if (rotated != bs) {
			w.setBlock(coord, rotated, 3);
			return InteractionResult.SUCCESS;
		}


		return InteractionResult.PASS;
	}
}
