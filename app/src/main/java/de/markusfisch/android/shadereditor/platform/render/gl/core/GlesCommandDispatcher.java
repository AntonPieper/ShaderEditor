package de.markusfisch.android.shadereditor.platform.render.gl.core;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;


/**
 * Dispatches {@link GpuCommand} instances to their corresponding {@link GlesCommandHandler}.
 * This class is responsible for managing a mapping of command types to their handlers and
 * executing the correct handler when a command is dispatched.
 *
 * <p>Usage:
 * <pre>{@code
 * GlesCommandDispatcher dispatcher = new GlesCommandDispatcher();
 * dispatcher.register(new MyCommandHandler());
 * dispatcher.dispatch(new MyCommand(), renderContext);
 * }</pre>
 *
 * <p>Handlers are registered based on the specific {@link GpuCommand} type they handle.
 * If a command is dispatched for which no handler is registered, an
 * {@link IllegalStateException} will be thrown. If an attempt is made to register a handler
 * for a command type that already has a registered handler, an {@link IllegalArgumentException}
 * will be thrown.
 */
public final class GlesCommandDispatcher {
	private final Map<Class<? extends GpuCommand>, GlesCommandHandler<?>> handlers =
			new HashMap<>();

	/**
	 * Executes the appropriate handler for the given command.
	 *
	 * <p>This method looks up the handler registered for the type of the given command.
	 * If no handler is found, an {@link IllegalStateException} is thrown.
	 * Otherwise, the handler's {@code dispatch} method is called with the command
	 * and the rendering context. The handler's dispatch method is responsible for
	 * safely casting the command to its specific type.
	 *
	 * @param command The command to execute. Must not be null.
	 * @param context The rendering context. Must not be null.
	 * @throws IllegalStateException if no handler is registered for the command's type.
	 */
	public void dispatch(@NonNull GpuCommand command, @NonNull GlesRenderContext context) {
		GlesCommandHandler<?> handler = handlers.get(command.getClass());
		if (handler == null) {
			throw new IllegalStateException("No handler registered for command: " + command.getClass().getName());
		}
		// The handler's default dispatch method performs the safe cast.
		handler.dispatch(command, context);
	}

	/**
	 * Registers a handler for a specific command type.
	 *
	 * @param <T>     The type of the command this handler can process.
	 * @param handler The handler to register.
	 * @return This dispatcher, for chaining.
	 * @throws IllegalArgumentException If a handler for this command type is already registered.
	 */
	public <T extends GpuCommand> GlesCommandDispatcher register(
			@NonNull GlesCommandHandler<T> handler) {
		if (handlers.put(handler.type(), handler) != null) {
			throw new IllegalArgumentException(
					"Handler for " + handler.type().getName() + " already registered.");
		}
		return this;
	}
}