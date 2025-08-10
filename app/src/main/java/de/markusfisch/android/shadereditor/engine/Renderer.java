package de.markusfisch.android.shadereditor.engine;


import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.scene.RenderPass;

public interface Renderer extends AutoCloseable {

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

	@Override
	void close();
}