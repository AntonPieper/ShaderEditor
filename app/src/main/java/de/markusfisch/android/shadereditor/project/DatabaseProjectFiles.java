package de.markusfisch.android.shadereditor.project;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.dao.TextureDao;
import de.markusfisch.android.shadereditor.graphics.BitmapEditor;
import de.markusfisch.android.shadereditor.project.legacy.CubemapAtlas;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.LibraryTexture;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.TextureFile;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles.Problem;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles.Stored;

/**
 * One database shader presented as project files: the shader converted
 * at {@link LegacyShaderConverter#SHADER}, the files the conversion
 * generates, and the library textures it reads as PNG images, cubemaps
 * repacked into strips. Reads the database and writes nothing; use it
 * off the main thread.
 */
public final class DatabaseProjectFiles implements ProjectFiles {
	@NonNull
	private final TextureDao textures;
	@NonNull
	private final LegacyShaderConverter.Result conversion;
	@NonNull
	private final Map<ProjectPath, TextureFile> textureFiles = new HashMap<>();
	// The library textures the conversion used, by name.
	@NonNull
	private final Map<String, Stored> used;
	@NonNull
	private final ProjectLimits limits;

	private DatabaseProjectFiles(
			@NonNull TextureDao textures,
			@NonNull LegacyShaderConverter.Result conversion,
			@NonNull Map<String, Stored> used,
			@NonNull ProjectLimits limits) {
		this.textures = textures;
		this.conversion = conversion;
		this.used = used;
		this.limits = limits;
		for (TextureFile file : conversion.textures()) {
			textureFiles.put(file.path(), file);
		}
	}

	/**
	 * Convert the shader with this id, or return null when there is no
	 * such shader. Textures are served within limits.
	 */
	@Nullable
	public static DatabaseProjectFiles open(
			@NonNull DataSource dataSource,
			long shaderId,
			@NonNull ProjectLimits limits) {
		DataRecords.Shader shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			return null;
		}
		String text = shader.fragmentShader();
		return convert(dataSource.texture, text != null ? text : "", limits);
	}

	/**
	 * Convert the text of a database shader, such as the unsaved text of
	 * one in the editor. Textures are served within limits.
	 */
	@NonNull
	public static DatabaseProjectFiles convert(
			@NonNull TextureDao textures,
			@NonNull String text,
			@NonNull ProjectLimits limits) {
		Map<String, Stored> library = StoredFiles.library(
				textures.getTextures(null),
				textures.getSamplerCubeTextures(null));
		Map<String, Stored> used = new HashMap<>();
		LegacyShaderConverter.Result conversion = LegacyShaderConverter.convert(
				text,
				new LegacyShaderConverter.Library() {
					@Override
					public LibraryTexture find(String name) {
						return DatabaseProjectFiles.find(textures, library,
								used, name);
					}

					@Override
					public LibraryTexture find(long id) {
						return DatabaseProjectFiles.find(textures, used, id);
					}
				});
		return new DatabaseProjectFiles(textures, conversion, used, limits);
	}

	/** The conversion, with its line map and diagnostics. */
	@NonNull
	public LegacyShaderConverter.Result conversion() {
		return conversion;
	}

	@Override
	public Read read(ProjectPath path) {
		String text = conversion.files().get(path);
		if (text != null) {
			return content(text.getBytes(StandardCharsets.UTF_8));
		}
		TextureFile file = textureFiles.get(path);
		if (file == null) {
			return null;
		}
		try {
			return readTexture(file);
		} catch (OutOfMemoryError e) {
			return failed(Problem.NO_MEMORY, file);
		} catch (RuntimeException e) {
			// A database error, or a bitmap the platform rejects.
			return failed(Problem.DATABASE, file);
		}
	}

	// Serves the row the conversion used, decoded at the size it used,
	// so a texture replaced since then fails instead of mixing the two.
	@NonNull
	private Read readTexture(@NonNull TextureFile file) {
		Stored stored = used.get(file.name());
		if (stored == null) {
			return failed(Problem.UNREADABLE, file);
		}
		String problem = StoredFiles.check(file.name(), stored.width(),
				stored.height(), file.cubemap(), limits.imagePixels());
		if (problem != null) {
			return new Failed(problem);
		}
		Long id = stored.id();
		Bitmap bitmap = id != null
				? textures.getTextureBitmap(id)
				: textures.getTextureBitmap(stored.name());
		if (bitmap == null) {
			return failed(id != null && textures.getTextureInfo(id) == null
					? Problem.REMOVED
					: Problem.UNREADABLE, file);
		}
		try {
			problem = StoredFiles.changed(stored, bitmap.getWidth(),
					bitmap.getHeight());
			if (problem != null) {
				return new Failed(problem);
			}
			byte[] png = file.cubemap()
					? strip(bitmap)
					: BitmapEditor.encodeAsPng(bitmap);
			if (png == null) {
				return failed(Problem.NOT_A_CUBEMAP, file);
			}
			return png.length > 0
					? content(png)
					: failed(Problem.NOT_ENCODED, file);
		} finally {
			bitmap.recycle();
		}
	}

	@NonNull
	private static Read failed(@NonNull Problem problem,
			@NonNull TextureFile file) {
		return new Failed(StoredFiles.reason(problem, file.name()));
	}

	@NonNull
	private static Read content(@NonNull byte[] bytes) {
		String revision = StoredFiles.revision(bytes);
		return revision != null
				? new Content(bytes, revision)
				: new Failed("SHA-256 is not available on this device");
	}

	// A texture that is neither square nor an atlas is in neither list,
	// so its size is only known once it is decoded.
	@Nullable
	private static LibraryTexture find(
			@NonNull TextureDao textures,
			@NonNull Map<String, Stored> library,
			@NonNull Map<String, Stored> used,
			@NonNull String name) {
		Stored stored = used.get(name);
		if (stored == null) {
			stored = library.get(name);
		}
		if (stored == null) {
			Bitmap bitmap = textures.getTextureBitmap(name);
			if (bitmap == null) {
				return null;
			}
			stored = new Stored(null, name, bitmap.getWidth(),
					bitmap.getHeight(), false);
			bitmap.recycle();
		}
		used.put(name, stored);
		return stored.texture();
	}

	// A backbuffer preset saved as a row id is served under that id.
	@Nullable
	private static LibraryTexture find(
			@NonNull TextureDao textures,
			@NonNull Map<String, Stored> used,
			long id) {
		String key = String.valueOf(id);
		Stored stored = used.get(key);
		if (stored == null) {
			DataRecords.TextureInfo info = textures.getTextureInfo(id);
			if (info == null) {
				return null;
			}
			stored = new Stored(id, key, info.width(), info.height(), false);
			used.put(key, stored);
		}
		return stored.texture();
	}

	// Allocates no more pixels than StoredFiles.check admitted.
	@Nullable
	private static byte[] strip(@NonNull Bitmap bitmap) {
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		int[] pixels = new int[width * height];
		bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
		int[] strip = CubemapAtlas.toStrip(pixels, width, height);
		if (strip == null) {
			return null;
		}
		int side = CubemapAtlas.faceSize(width, height);
		Bitmap faces = Bitmap.createBitmap(side * 6, side,
				Bitmap.Config.ARGB_8888);
		try {
			faces.setPremultiplied(false);
			faces.setPixels(strip, 0, side * 6, 0, 0, side * 6, side);
			return BitmapEditor.encodeAsPng(faces);
		} finally {
			faces.recycle();
		}
	}
}
