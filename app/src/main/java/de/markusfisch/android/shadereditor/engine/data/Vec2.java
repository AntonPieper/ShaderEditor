package de.markusfisch.android.shadereditor.engine.data;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record Vec2(float x, float y) {
	@NonNull
	@Contract("_ -> new")
	public static Vec2 from(@NonNull float[] values) {
		return new Vec2(values[0], values[1]);
	}
}
