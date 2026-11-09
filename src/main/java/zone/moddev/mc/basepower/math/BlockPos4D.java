package zone.moddev.mc.basepower.math;

import net.minecraft.nbt.Tag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * This class is used to store 4D integer coordinates
 * @author DrCyano
 *
 */
public class BlockPos4D {
	/**
	 * 4th dimension coordinate
	 */
	public final ResourceKey<Level> dimension;
	/**
	 * X,Y,Z coordinate
	 */
	public final BlockPos pos;

	/**
	 * Gets the dimension of this position
	 *
	 * @return The dimension of the coordinate (e.g. 0 for overworld, -1 for nether, 1 for the end)
	 */
	public ResourceKey<Level> getDim() {
		return dimension;
	}

	/**
	 * Gets the x coordinate of this position
	 *
	 * @return The x value (east-west)
	 */
	public int getX() {
		return pos.getX();
	}

	/**
	 * Gets the y coordinate of this position
	 *
	 * @return The y value (down-up)
	 */
	public int getY() {
		return pos.getY();
	}

	/**
	 * Gets the z coordinate of this position
	 *
	 * @return The x value (north-south)
	 */
	public int getZ() {
		return pos.getZ();
	}

	private final int hashCache;

	/**
	 * Creates an integer pair to be used as 4D coordinates
	 *
	 * @param d D-coordinate of D,X,Y,Z coordinate tuple
	 * @param x X-coordinate of D,X,Y,Z coordinate tuple
	 * @param y Y-coordinate of D,X,Y,Z coordinate tuple
	 * @param z Z-coordinate of D,X,Y,Z coordinate tuple
	 */
	public BlockPos4D(ResourceKey<Level> d, int x, int y, int z) {
		this(d, new BlockPos(x, y, z));
	}

	/**
	 * Makes a 4D coordinate from a dimension index and block position
	 *
	 * @param dimension Dimension of the block
	 * @param bp        location of the block
	 */
	public BlockPos4D(ResourceKey<Level> dimension, BlockPos bp) {
		this.dimension = java.util.Objects.requireNonNull(dimension, "dimension");
		this.pos = bp.immutable();
		hashCache = (((dimension.hashCode() * 31 + pos.getZ()) * 31 + pos.getX()) * 31 + pos.getY());
	}

	/**
	 * Hash code implementation for use in hashed indices
	 */
	@Override
	public int hashCode() {
		return hashCache;
	}

	/**
	 * Equals implementation to go with the hashCode implementation
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) return true;
		if (other instanceof BlockPos4D && other.hashCode() == this.hashCode()) {
			BlockPos4D o = (BlockPos4D) other;
			return this.dimension.equals(o.dimension) && this.pos.equals(o.pos);
		}
		return false;
	}

	/**
	 * Moves the position in a direction given
	 *
	 * @param direction how far to move the coordinate
	 * @param distance  The direction to move the coordinate
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D relative(final Direction direction, final int distance) {
		return new BlockPos4D(this.dimension,
				this.getX() + direction.getStepX() * distance,
				this.getY() + direction.getStepY() * distance,
				this.getZ() + direction.getStepZ() * distance);
	}

	/**
	 * Moves the position in a given direction by 1 block
	 *
	 * @param direction The direction to move the coordinate
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D relative(final Direction direction) {
		return relative(direction, 1);
	}


	/**
	 * Moves the position along all three X-Y-Z spacial coordinates to a new position
	 *
	 * @param dx How far to move along the X coordinate
	 * @param dy How far to move along the Y coordinate
	 * @param dz How far to move along the Z coordinate
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D add(final int dx, final int dy, final int dz) {
		return new BlockPos4D(this.dimension, pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
	}

	/**
	 * Moves the position by 1 block
	 *
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D up() {
		return this.up(1);
	}

	/**
	 * Moves the block up (+Y)
	 *
	 * @param dist The number of blocks to move the position in this direction
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D up(final int dist) {
		return new BlockPos4D(this.dimension, pos.getX(), pos.getY() + dist, pos.getZ());
	}

	/**
	 * Moves the position by 1 block
	 *
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D down() {
		return this.down(1);
	}

	/**
	 * Moves the block down (-Y)
	 *
	 * @param dist The number of blocks to move the position in this direction
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D down(final int dist) {
		return new BlockPos4D(this.dimension, pos.getX(), pos.getY() - dist, pos.getZ());
	}

	/**
	 * Moves the position by 1 block
	 *
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D north() {
		return this.north(1);
	}

	/**
	 * Moves the block north (-Z)
	 *
	 * @param dist The number of blocks to move the position in this direction
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D north(final int dist) {
		return new BlockPos4D(this.dimension, pos.getX(), pos.getY(), pos.getZ() - dist);
	}

	/**
	 * Moves the position by 1 block
	 *
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D south() {
		return this.south(1);
	}

	/**
	 * Moves the block south (+Z)
	 *
	 * @param dist The number of blocks to move the position in this direction
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D south(final int dist) {
		return new BlockPos4D(this.dimension, pos.getX(), pos.getY(), pos.getZ() + dist);
	}

	/**
	 * Moves the position by 1 block
	 *
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D west() {
		return this.west(1);
	}

	/**
	 * Moves the block west (-X)
	 *
	 * @param dist The number of blocks to move the position in this direction
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D west(final int dist) {
		return new BlockPos4D(this.dimension, pos.getX() - dist, pos.getY(), pos.getZ());
	}

	/**
	 * Moves the position by 1 block
	 *
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D east() {
		return this.east(1);
	}

	/**
	 * Moves the block east (+X)
	 *
	 * @param dist The number of blocks to move the position in this direction
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D east(final int dist) {
		return new BlockPos4D(this.dimension, pos.getX() + dist, pos.getY(), pos.getZ());
	}

	/**
	 * @param newDimension The dimension of the coordinate (e.g. 0 for overworld, -1 for nether,
	 *                     1 for the end)
	 * @return A new BlockPos4D representing the new position
	 */
	public BlockPos4D toDimension(ResourceKey<Level> newDimension) {
		return new BlockPos4D(newDimension, this.pos);
	}

	public BlockPos4D[] getNeighbors() {
		BlockPos4D[] n = new BlockPos4D[Direction.values().length];
		for (int i = 0; i < n.length; i++) {
			n[i] = this.relative(Direction.values()[i]);
		}
		return n;
	}

	public Tag toNBT() {
		CompoundTag data = new CompoundTag();
		data.putString("Dimension", dimension.location().toString());
		data.putIntArray("Pos", new int[] {pos.getX(), pos.getY(), pos.getZ()});
		return data;
	}

	public static BlockPos4D fromNBT(Tag tag) {
		if (tag instanceof IntArrayTag) {
			int[] data = ((IntArrayTag) tag).getAsIntArray();
			if (data.length != 4) throw new IllegalArgumentException("Invalid legacy position");
			return new BlockPos4D(legacyDimension(data[0]), data[1], data[2], data[3]);
		}
		CompoundTag data = (CompoundTag) tag;
		int[] pos = data.getIntArray("Pos");
		if (pos.length != 3) throw new IllegalArgumentException("Invalid position");
		return new BlockPos4D(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				new ResourceLocation(data.getString("Dimension"))), pos[0], pos[1], pos[2]);
	}

	/** Only the three vanilla numeric IDs have an unambiguous legacy meaning. */
	public static ResourceKey<Level> legacyDimension(int dimension) {
		switch (dimension) {
			case 0: return Level.OVERWORLD;
			case -1: return Level.NETHER;
			case 1: return Level.END;
			default: throw new IllegalArgumentException("No registry key for legacy dimension " + dimension);
		}
	}

	@Override
	public String toString() {
		return "[" + dimension + "," + pos.getX() + "," + pos.getY() + "," + pos.getZ() + "]";
	}
}
