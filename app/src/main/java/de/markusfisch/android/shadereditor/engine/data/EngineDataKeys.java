package de.markusfisch.android.shadereditor.engine.data;

import de.markusfisch.android.shadereditor.engine.Viewport;

public class EngineDataKeys {
	/**
	 * Provides the actual, physical resolution of the surface view in pixels.
	 * This value changes on orientation or window size changes.
	 */
	public static final DataKey<Viewport> PHYSICAL_VIEWPORT_RESOLUTION = DataKey.of(
			"engine.viewport.physicalResolution", Viewport.class);

	/**
	 * Provides the target resolution for rendering, which may be scaled down
	 * from the physical resolution for performance. The `resolution` uniform
	 * in shaders should typically be bound to this value.
	 */
	public static final DataKey<Viewport> RENDER_TARGET_RESOLUTION = DataKey.of(
			"engine.viewport.renderTargetResolution", Viewport.class);

	private EngineDataKeys() {
		throw new UnsupportedOperationException(
				"This is a utility class and cannot be instantiated");
	}
}
