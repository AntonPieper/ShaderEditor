package de.markusfisch.android.shadereditor.project;

import android.graphics.Bitmap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
	@NonNull
	private final Map<String, DataRecords.TextureInfo> library;

	private DatabaseProjectFiles(
			@NonNull TextureDao textures,
			@NonNull LegacyShaderConverter.Result conversion,
			@NonNull Map<String, DataRecords.TextureInfo> library) {
		this.textures = textures;
		this.conversion = conversion;
		this.library = library;
		for (TextureFile file : conversion.textures()) {
			textureFiles.put(file.path(), file);
		}
	}

	/**
	 * Convert the shader with this id, or return null when there is no
	 * such shader.
	 */
	@Nullable
	public static DatabaseProjectFiles open(
			@NonNull DataSource dataSource,
			long shaderId) {
		DataRecords.Shader shader = dataSource.shader.getShader(shaderId);
		if (shader == null) {
			return null;
		}
		TextureDao textures = dataSource.texture;
		Map<String, DataRecords.TextureInfo> library = new HashMap<>();
		Set<String> atlases = new HashSet<>();
		List<DataRecords.TextureInfo> cubemaps =
				textures.getSamplerCubeTextures(null);
		add(library, textures.getTextures(null));
		add(library, cubemaps);
		for (DataRecords.TextureInfo info : cubemaps) {
			if (library.get(info.name()) == info) {
				atlases.add(info.name());
			}
		}
		String text = shader.fragmentShader();
		LegacyShaderConverter.Result conversion = LegacyShaderConverter.convert(
				text != null ? text : "",
				name -> find(textures, library, atlases, name));
		return new DatabaseProjectFiles(textures, conversion, library);
	}

	/** The conversion, with its line map and diagnostics. */
	@NonNull
	public LegacyShaderConverter.Result conversion() {
		return conversion;
	}

	@Override
	public Content read(ProjectPath path) {
		String text = conversion.files().get(path);
		if (text != null) {
			return new Content(text.getBytes(StandardCharsets.UTF_8),
					"text:" + text.hashCode());
		}
		TextureFile file = textureFiles.get(path);
		if (file == null) {
			return null;
		}
		Bitmap bitmap = textures.getTextureBitmap(file.name());
		if (bitmap == null) {
			return null;
		}
		try {
			byte[] png = file.cubemap()
					? strip(bitmap)
					: BitmapEditor.encodeAsPng(bitmap);
			DataRecords.TextureInfo info = library.get(file.name());
			return png != null && png.length > 0
					? new Content(png, "texture:" + (info != null
					? String.valueOf(info.id())
					: file.name()) + (file.cubemap() ? ":strip" : ""))
					: null;
		} finally {
			bitmap.recycle();
		}
	}

	// The old renderer read the first row with a name, which is the
	// lowest id.
	private static void add(
			@NonNull Map<String, DataRecords.TextureInfo> library,
			@NonNull List<DataRecords.TextureInfo> textures) {
		for (DataRecords.TextureInfo info : textures) {
			DataRecords.TextureInfo known = library.get(info.name());
			if (known == null || info.id() < known.id()) {
				library.put(info.name(), info);
			}
		}
	}

	// A texture that is neither square nor an atlas is in neither list.
	@Nullable
	private static LibraryTexture find(
			@NonNull TextureDao textures,
			@NonNull Map<String, DataRecords.TextureInfo> library,
			@NonNull Set<String> atlases,
			@NonNull String name) {
		DataRecords.TextureInfo info = library.get(name);
		if (info != null) {
			return new LibraryTexture(atlases.contains(name), info.width(),
					info.height());
		}
		Bitmap bitmap = textures.getTextureBitmap(name);
		if (bitmap == null) {
			return null;
		}
		try {
			return new LibraryTexture(false, bitmap.getWidth(),
					bitmap.getHeight());
		} finally {
			bitmap.recycle();
		}
	}

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
