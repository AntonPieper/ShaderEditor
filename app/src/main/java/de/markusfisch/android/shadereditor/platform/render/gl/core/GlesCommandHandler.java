package de.markusfisch.android.shadereditor.platform.render.gl.core;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;

public interface GlesCommandHandler<T extends GpuCommand> {
	/**
	 * @return The specific GpuCommand class this handler is responsible for.
	 */
	@NonNull
	Class<T> type();

	/**
	 * Executes the logic for the given command.
	 *
	 * @param cmd The typed command to handle.
	 * @param ctx The current rendering context.
	 */
	void handle(@NonNull T cmd, @NonNull GlesRenderContext ctx);

	/**
	 * Dispatches a generic GpuCommand to the typed handle method.
	 * This default implementation provides a safe, down-casting bridge.
	 *
	 * @param command The generic command to dispatch.
	 * @param context The current rendering context.
	 */
	@SuppressWarnings("DataFlowIssue")
	default void dispatch(@NonNull GpuCommand command, @NonNull GlesRenderContext context) {
		handle(type().cast(command), context);
	}
}