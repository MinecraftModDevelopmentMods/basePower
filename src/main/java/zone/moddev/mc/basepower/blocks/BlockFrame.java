package zone.moddev.mc.basepower.blocks;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * This class is for blocks that should act like glass (renderring-wise), but can be harvested 
 * normally.
 * @author DrCyano
 *
 */
public class BlockFrame extends Block {

	public BlockFrame(Properties properties) {
		super(properties.sound(SoundType.METAL).noOcclusion().dynamicShape());
	}

	public boolean isFullCube(BlockState bs) {
		return false;
	}

	public boolean canSilkHarvest(BlockState state, LevelReader world, BlockPos pos, Player player) {
		return true;
	}


	public boolean isOpaqueCube(BlockState bs) {
		return false;
	}

}
