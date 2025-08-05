package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record Mat2(float m00, float m01, float m10, float m11) {
	@NonNull
	@Contract(" -> new")
	public static Mat2 identity() {
		return new Mat2(1, 0, 0, 1);
	}

	@NonNull
	@Contract("_ -> new")
	public static Mat2 from(@NonNull float[] values) {
		return new Mat2(values[0], values[1], values[2], values[3]);
	}

	@NonNull
	@Contract("_, _ -> new")
	public static Vec2 mul(@NonNull Mat2 m, @NonNull Vec2 v) {
		return new Vec2(
				m.m00 * v.x() + m.m01 * v.y(),
				m.m10 * v.x() + m.m11 * v.y()
		);
	}
}
