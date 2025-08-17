package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

public record Viewport(int width, int height) {
	@FunctionalInterface
	public interface ComponentMapper {
		int mapComponent(int value);
	}

	@FunctionalInterface
	public interface ViewportMapper {
		@NonNull
		Viewport map(int width, int height);
	}

	@NonNull
	@Contract(value = "_ -> new", pure = true)
	public static float[] toVec2(@NonNull Viewport viewport) {
		return new float[]{(float) viewport.width, (float) viewport.height};
	}

	@NonNull
	@Contract(value = "_ -> new", pure = true)
	public Viewport map(@NonNull ComponentMapper operation) {
		return new Viewport(
				operation.mapComponent(width()),
				operation.mapComponent(height()));
	}

	@NonNull
	@Contract(value = "_ -> new", pure = true)
	public Viewport map(@NonNull ViewportMapper operation) {
		return operation.map(width(), height());
	}
}
