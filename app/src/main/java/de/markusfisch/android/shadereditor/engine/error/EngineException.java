package de.markusfisch.android.shadereditor.engine.error;

import androidx.annotation.NonNull;

/**
 * A custom RuntimeException to wrap any EngineError.
 * This allows the engine's internal methods to throw checked-style
 * rich errors without forcing every single method signature in the
 * call stack to declare "throws". The bridge at the top level
 * will catch this specific exception.
 */
public class EngineException extends RuntimeException {
	@NonNull
	private final EngineError error;

	public EngineException(@NonNull EngineError error) {
		super(error.message(), error.cause());
		this.error = error;
	}

	@NonNull
	public EngineError getError() {
		return error;
	}
}