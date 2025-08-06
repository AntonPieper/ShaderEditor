package de.markusfisch.android.shadereditor.engine;


import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.model.RenderPass;

public interface Renderer {
	/**
	 * Executes a given render pass.
	 *
	 * @param renderPass The description of what to render.
	 */
	void render(@NonNull RenderPass renderPass);

	/**
	 * Called when the surface is created or recreated.
	 */
	void onSurfaceCreated();

	/**
	 * Called when the surface dimensions change.
	 */
	void onSurfaceChanged(int width, int height);
}