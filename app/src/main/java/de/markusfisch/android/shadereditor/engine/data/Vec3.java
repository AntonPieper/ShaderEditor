package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record Vec3(float x, float y, float z) {
	@NonNull
	@Contract("_ -> new")
	public static Vec3 from(@NonNull float[] values) {
		return new Vec3(values[0], values[1], values[2]);

	}
}
