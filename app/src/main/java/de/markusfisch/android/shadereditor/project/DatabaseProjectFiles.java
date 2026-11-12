package de.markusfisch.android.shadereditor.project;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

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
	 * such shader. Textures are served within limits; reserved are the
	 * names of the builtin uniforms.
	 */
	@Nullable
	public static DatabaseProjectFiles open(
			@NonNull DataSource dataSource,
			long shaderId,
			@NonNull ProjectLimits limits,
			@NonNull Set<String> reserved) {
		DataRecords.Shader shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			return null;
		}
		String text = shader.fragmentShader();
		return convert(dataSource.texture, text != null ? text : "", limits,
				reserved);
	}

	/**
	 * Convert the text of a database shader, such as the unsaved text of
	 * one in the editor. Textures are served within limits; reserved are
	 * the names of the builtin uniforms.
	 */
	@NonNull
	public static DatabaseProjectFiles convert(
			@NonNull TextureDao textures,
			@NonNull String text,
			@NonNull ProjectLimits limits,
			@NonNull Set<String> reserved) {
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
				}, reserved);
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
	// Both sizes are checked before the pixels are decoded.
	@NonNull
	private Read readTexture(@NonNull TextureFile file) {
		Stored stored = used.get(file.name());
		Long id = stored != null ? stored.id() : null;
		if (id == null) {
			return failed(Problem.UNREADABLE, file);
		}
		String problem = StoredFiles.check(file.name(), stored.width(),
				stored.height(), file.cubemap(), limits.imagePixels());
		if (problem != null) {
			return new Failed(problem);
		}
		byte[] data = textures.getTextureData(id);
		if (data == null) {
			return failed(textures.getTextureInfo(id) == null
					? Problem.REMOVED
					: Problem.UNREADABLE, file);
		}
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(data, 0, data.length, options);
		problem = StoredFiles.changed(stored, options.outWidth,
				options.outHeight);
		if (problem != null) {
			return new Failed(problem);
		}
		options = new BitmapFactory.Options();
		options.inPremultiplied = false;
		Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length,
				options);
		if (bitmap == null) {
			return failed(Problem.UNREADABLE, file);
		}
		try {
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

	// A texture that is neither square nor an atlas is in neither list;
	// its row says its size.
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
			DataRecords.TextureInfo info = textures.getTextureInfo(name);
			if (info == null) {
				return null;
			}
			stored = new Stored(info.id(), name, info.width(), info.height(),
					false);
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
