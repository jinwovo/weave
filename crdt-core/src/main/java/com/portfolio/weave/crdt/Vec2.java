package com.portfolio.weave.crdt;

/** An immutable 2D vector, used for both a shape's position and its size on the canvas. */
public record Vec2(double x, double y) {

	public static final Vec2 ZERO = new Vec2(0, 0);
}
