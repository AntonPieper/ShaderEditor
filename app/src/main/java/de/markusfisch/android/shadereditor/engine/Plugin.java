package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;

public interface Plugin {
	/**
	 * Called once when the plugin is registered.
	 */
	default void onSetup(@NonNull Engine engine) {
	}

	/**
	 * Called before the engine processes the render queue.
	 */
	default void onPreRender(@NonNull Engine engine) {
	}

	/**
	 * Called by the engine to ask plugins to submit their render passes for this frame.
	 * This is the primary hook for defining what gets drawn.
	 */
	default void onRender(@NonNull Engine engine) {
	}

	/**
	 * Called after the engine has processed the render queue.
	 */
	default void onPostRender(@NonNull Engine engine) {
	}

	/**
	 * Called when the engine is shutting down.
	 */
	default void onTeardown(@NonNull Engine engine) {
	}
}