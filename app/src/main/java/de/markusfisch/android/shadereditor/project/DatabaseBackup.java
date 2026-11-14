package de.markusfisch.android.shadereditor.project;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.project.legacy.KeptText;

/**
 * The shaders of the database as the move into the library reads
 * them. It writes nothing; use it off the main thread. What the
 * database throws is thrown on: the move takes it as the failure of
 * the one shader it was reading.
 */
public final class DatabaseBackup implements LibraryMove.Source {
	@NonNull
	private final DataSource dataSource;
	@NonNull
	private final ProjectLimits limits;
	@NonNull
	private final Set<String> reserved;

	/** @param reserved the names of the builtin uniforms */
	public DatabaseBackup(@NonNull DataSource dataSource,
			@NonNull ProjectLimits limits, @NonNull Set<String> reserved) {
		this.dataSource = dataSource;
		this.limits = limits;
		this.reserved = reserved;
	}

	// No text and no thumbnail is read to list them.
	@Override
	public List<LibraryMove.Shader> shaders() {
		List<LibraryMove.Shader> shaders = new ArrayList<>();
		for (DataRecords.ShaderRow row : dataSource.shader.getShaderRows()) {
			shaders.add(new LibraryMove.Shader(row.id(), row.name(),
					row.quality() > 0 ? row.quality() : 1f,
					time(row.modified()), row.size()));
		}
		return shaders;
	}

	@Override
	public LibraryMove.Converted convert(long id) {
		DatabaseProjectFiles files = DatabaseProjectFiles.open(dataSource,
				id, limits, reserved);
		if (files == null) {
			return null;
		}
		KeptText.Kept kept = files.kept();
		return new LibraryMove.Converted(files.conversion(), kept.shader(),
				kept.loss(), files, dataSource.shader.getThumbnail(id));
	}

	// As the database writes it, in the time zone of the device.
	private static long time(@Nullable String modified) {
		if (modified == null) {
			return 0;
		}
		try {
			Date date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
					Locale.US).parse(modified);
			return date != null ? date.getTime() : 0;
		} catch (ParseException e) {
			return 0;
		}
	}
}
