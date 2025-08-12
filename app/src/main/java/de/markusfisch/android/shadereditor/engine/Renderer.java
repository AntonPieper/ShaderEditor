package de.markusfisch.android.shadereditor.engine;


import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.pipeline.CommandBuffer;

public interface Renderer extends AutoCloseable {

	/**
	 * Executes a command buffer.
	 *
	 * @param commands The command buffer to execute.
	 */
	void execute(@NonNull CommandBuffer commands);

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