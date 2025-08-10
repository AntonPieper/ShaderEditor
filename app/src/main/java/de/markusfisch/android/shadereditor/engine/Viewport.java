package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record Viewport(int width, int height) {
	@NonNull
	@Contract(value = "_ -> new", pure = true)
	public static float[] toVec2(@NonNull Viewport viewport) {
		return new float[]{(float) viewport.width, (float) viewport.height};
	}
}
