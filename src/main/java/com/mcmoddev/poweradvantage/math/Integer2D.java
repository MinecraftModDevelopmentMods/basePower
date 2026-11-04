package com.mcmoddev.poweradvantage.math;

/**
 * Legacy coordinate type retained because it appears in the public
 * {@code cyano.poweradvantage.api.simple.SimpleMachineGUI} constructor.
 *
 * @deprecated New code should use
 * {@link zone.moddev.mc.basepower.math.Integer2D}.
 */
@Deprecated
public class Integer2D {
	public final int X;
	public final int Y;

	public Integer2D(int x, int y) {
		this.X = x;
		this.Y = y;
	}

	public static Integer2D[] fromCoordinates(int... xy) {
		Integer2D[] arr = new Integer2D[xy.length / 2];
		for (int i = 0; i < arr.length; i++) {
			arr[i] = new Integer2D(xy[2 * i], xy[2 * i + 1]);
		}
		return arr;
	}
}
