package zone.moddev.mc.basepower.math;

/**
 * This class is used to store 2D integer coordinates
 * @author DrCyano
 *
 */
public final class Integer2D extends com.mcmoddev.poweradvantage.math.Integer2D {

	/**
	 * Creates an integer pair to be used as 2D coordinates
	 *
	 * @param x X-coordinate of X,Y coordinate pair
	 * @param y Y-coordinate of X,Y coordinate pair
	 */
	public Integer2D(int x, int y) {
		super(x, y);
	}

	/**
	 * Generates an array of Integer2D objects from a series of integers
	 *
	 * @param xy Series of coordinates in order of x0, y0, x1, y1, ...
	 * @return Array of Integer2D instances
	 */
	public static Integer2D[] fromCoordinates(int... xy) {
		Integer2D[] arr = new Integer2D[xy.length / 2];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = new Integer2D(xy[2 * i], xy[2 * i + 1]);
		}
		return arr;
	}
}
