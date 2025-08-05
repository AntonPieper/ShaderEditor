package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record Vec4(float x, float y, float z, float w) {
	@NonNull
	@Contract("_ -> new")
	public static Vec4 from(@NonNull float[] values) {
		return new Vec4(values[0], values[1], values[2], values[3]);

	}
}
