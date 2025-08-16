package de.markusfisch.android.shadereditor.engine.error;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * A sealed interface for all possible engine errors.
 * This allows for exhaustive checks in `switch` expressions or `instanceof` patterns,
 * providing a type-safe way to handle different failure scenarios.
 */
public sealed interface EngineError {
	/**
	 * @return A generic, user-friendly message describing the error.
	 */
	@NonNull
	String message();

	/**
	 * @return The underlying cause (another exception), if any.
	 */
	@Nullable
	Throwable cause();

	@Override
	boolean equals(Object obj);

	@Override
	int hashCode();

	/**
	 * Represents a failure to load a requested asset.
	 *
	 * @param identifier A string representation of the asset that failed to load (e.g., URI or
	 *                   alias).
	 * @param message    A detailed error message.
	 * @param cause      The original exception that caused the failure (e.g., IOException).
	 */
	record AssetLoadError(
			@NonNull String identifier,
			@NonNull String message,
			@Nullable Throwable cause) implements EngineError {
	}

	/**
	 * Represents a failure during shader compilation or linking.
	 *
	 * @param message         A summary of the compilation/linking failure.
	 * @param details         The raw info log from the graphics driver.
	 * @param sourceLocations A list of parsed error locations.
	 * @param cause           The original exception.
	 */
	record ShaderCompilationError(
			@NonNull String message,
			@NonNull String details,
			@NonNull List<SourceLocation> sourceLocations,
			@Nullable Throwable cause) implements EngineError {
	}

	/**
	 * Represents a specific location of an error in source code.
	 *
	 * @param line    The line number where the error occurred. Can be -1 if not applicable.
	 * @param message The specific error message for this location.
	 */
	record SourceLocation(int line, @NonNull String message) {
	}

	/**
	 * A general-purpose error for issues not covered by more specific types.
	 *
	 * @param message A description of what went wrong.
	 * @param cause   The original exception, if any.
	 */
	record GenericError(
			@NonNull String message,
			@Nullable Throwable cause) implements EngineError {
	}
}