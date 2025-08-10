package de.markusfisch.android.shadereditor.engine.data;

import de.markusfisch.android.shadereditor.engine.Viewport;

public class EngineDataKeys {
	public static final DataKey<Viewport> VIEWPORT_RESOLUTION = DataKey.of(
			"engine.viewport.resolution", Viewport.class);

	private EngineDataKeys() {
		throw new UnsupportedOperationException(
				"This is a utility class and cannot be instantiated");
	}
}
