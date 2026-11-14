package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.database.dao.ShaderDao;

/**
 * The one way the editor's shader makes a new row of the database:
 * for a text that has none yet, for a copy, for a sample. Where the
 * shaders are files it is given no database, so there it can make
 * none, whatever asks: a new shader is a file of the library, and a
 * row made behind it would turn up as a file nobody made at the next
 * start.
 */
final class ShaderInserts {
	// Null where the shaders are files.
	@Nullable
	private final ShaderDao shaders;

	/**
	 * @param shaders the shaders of the database
	 * @param files   whether the shaders are files
	 */
	ShaderInserts(@NonNull ShaderDao shaders, boolean files) {
		this.shaders = files ? null : shaders;
	}

	/** Return whether a row can be made at all. */
	boolean possible() {
		return shaders != null;
	}

	/** Return the id of the new row, or 0 when none was made. */
	long insert(@NonNull String text, @Nullable String name,
			@Nullable byte[] thumbnail, float quality) {
		return shaders != null
				? shaders.insertShader(text, name, thumbnail, quality)
				: 0;
	}

	/** Return the id of the new row, or 0 when none was made. */
	long insertFromResource(@NonNull Context context, @Nullable String name,
			int sourceId, int thumbId, float quality) {
		return shaders != null
				? shaders.insertShaderFromResource(context, name, sourceId,
						thumbId, quality)
				: 0;
	}
}
