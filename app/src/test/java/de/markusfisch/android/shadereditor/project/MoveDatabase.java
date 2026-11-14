package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.LibraryMove.Converted;
import de.markusfisch.android.shadereditor.project.LibraryMove.Shader;
import de.markusfisch.android.shadereditor.project.legacy.KeptText;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.LibraryTexture;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.TextureFile;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * A database of shaders and library textures as the move reads one,
 * converted by the real converter. Nothing here can write to it.
 */
final class MoveDatabase implements LibraryMove.Source {
	static final long DAY = 86_400_000L;

	// A shader of the database, and nothing that writes to it.
	record Row(long id, String title, String text, float quality,
			long modified, byte[] thumbnail) {
	}

	final List<Row> rows = new ArrayList<>();
	final Map<String, LibraryTexture> textures = new HashMap<>();
	final Map<String, byte[]> images = new HashMap<>();
	// Textures that cannot be read now, and why.
	final Map<String, String> broken = new HashMap<>();
	// Shaders whose row cannot be read now, and why listing fails.
	final java.util.Set<Long> unreadable = new java.util.HashSet<>();
	String unlistable;
	int conversions;

	MoveDatabase add(long id, String title, String text) {
		rows.add(new Row(id, title, text, 1f, id * DAY,
				Pictures.png((int) id + 10)));
		return this;
	}

	@Override
	public List<Shader> shaders() {
		if (unlistable != null) {
			throw new IllegalStateException(unlistable);
		}
		List<Shader> shaders = new ArrayList<>();
		for (Row row : rows) {
			shaders.add(new Shader(row.id, row.title, row.quality,
					row.modified, row.text.getBytes(
							StandardCharsets.UTF_8).length));
		}
		return shaders;
	}

	@Override
	public Converted convert(long id) {
		for (Row row : rows) {
			if (row.id == id) {
				if (unreadable.contains(id)) {
					throw new IllegalStateException(
							"database read failed");
				}
				++conversions;
				return converted(row);
			}
		}
		return null;
	}

	private Converted converted(Row row) {
		LegacyShaderConverter.Result conversion =
				LegacyShaderConverter.convert(row.text, textures::get,
						BuiltinNames.ALL.names());
		ProjectFiles files = path -> {
			String text = conversion.files().get(path);
			if (text != null) {
				return content(text.getBytes(StandardCharsets.UTF_8));
			}
			for (TextureFile texture : conversion.textures()) {
				if (texture.path().equals(path)) {
					String problem = broken.get(texture.name());
					return problem != null
							? new ProjectFiles.Failed(problem)
							: content(images.get(texture.name()));
				}
			}
			return null;
		};
		KeptText.Kept kept = KeptText.of(row.text, conversion);
		return new Converted(conversion, kept.shader(), kept.loss(), files,
				row.thumbnail);
	}

	private static ProjectFiles.Read content(byte[] bytes) {
		return bytes != null ? new ProjectFiles.Content(bytes,
				StoredFiles.revision(bytes)) : null;
	}
}
