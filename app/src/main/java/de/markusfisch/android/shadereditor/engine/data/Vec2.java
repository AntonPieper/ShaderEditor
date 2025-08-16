package de.markusfisch.android.shadereditor.engine.data;

public record Vec2(float x, float y) {
	public Vec2(float v) {
		this(v, v);
	}

	public Vec2() {
		this(0f);
	}
}
