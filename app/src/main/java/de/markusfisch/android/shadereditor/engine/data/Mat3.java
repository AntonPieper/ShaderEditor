package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record Mat3(float m00, float m01, float m02, float m10, float m11, float m12, float m20,
		float m21, float m22) {

	@NonNull
	@Contract(" -> new")
	public static Mat3 identity() {
		return new Mat3(1, 0, 0, 0, 1, 0, 0, 0, 1);
	}

	@NonNull
	@Contract("_ -> new")
	public static Mat3 from(@NonNull float[] values) {
		return new Mat3(values[0], values[1], values[2], values[3], values[4], values[5],
				values[6],
				values[7], values[8]);
	}

	@NonNull
	@Contract("_ -> new")
	public Vec3 mul(@NonNull Vec3 v) {
		return new Vec3(
				m00 * v.x() + m01 * v.y() + m02 * v.z(),
				m10 * v.x() + m11 * v.y() + m12 * v.z(),
				m20 * v.x() + m21 * v.y() + m22 * v.z()
		);
	}
}
