package de.markusfisch.android.shadereditor.database;

import androidx.annotation.Nullable;

/**
 * Contains immutable data records representing database entities.
 */
public final class DataRecords {
	interface WithTitle {
		@Nullable
		String name();

		@Nullable
		String modified();

		@Nullable
		default String getTitle() {
			var name = name();
			return name == null || name.isEmpty() ? modified() : name;
		}
	}

	/**
	 * Holds summary data for a shader, suitable for list displays.
	 */
	public record ShaderInfo(
			long id,
			@Nullable
			String name,
			@Nullable
			String modified,
			@Nullable
			byte[] thumb
	) implements WithTitle {
	}

	/**
	 * Holds what is small of a shader: no text and no thumbnail, but
	 * how many bytes its text has.
	 */
	public record ShaderRow(
			long id,
			@Nullable
			String name,
			@Nullable
			String modified,
			float quality,
			long size
	) {
	}

	/**
	 * Holds the complete data for a single shader.
	 */
	public record Shader(
			long id,
			String fragmentShader,
			@Nullable
			String name,
			@Nullable
			String modified,
			float quality
	) implements WithTitle {
	}

	/**
	 * Holds summary data for a texture, suitable for list displays.
	 */
	public record TextureInfo(
			long id,
			String name,
			int width,
			int height,
			byte[] thumb
	) {
	}
}