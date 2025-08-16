package de.markusfisch.android.shadereditor.engine.data;

public final class InteractionDataKeys {
	/**
	 * Provides the touch position (float[2]) in pixels.
	 * The coordinate system's origin (0,0) is at the top-left of the view.
	 * If there is no touch, the value might be stale or a default (e.g., 0,0).
	 */
	public static final DataKey<Vec2> TOUCH_POSITION = DataKey.of(
			"interaction.touch.position", Vec2.class);

	/**
	 * Provides the wallpaper offset (float[2]) as x and y values, typically from 0.0 to 1.0.
	 * This is primarily used in the context of a live wallpaper.
	 */
	public static final DataKey<Vec2> WALLPAPER_OFFSET = DataKey.of(
			"interaction.wallpaper.offset", Vec2.class);

	private InteractionDataKeys() {
		throw new UnsupportedOperationException(
				"This is a utility class and cannot be instantiated");
	}
}