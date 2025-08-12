package de.markusfisch.android.shadereditor.platform.asset;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Objects;

import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.engine.AssetStreamProvider;

/**
 * An Android-specific implementation of {@link AssetStreamProvider}.
 * It resolves asset identifiers (URIs) to InputStreams using Android's
 * ContentResolver for standard URIs (e.g., "content://") and a custom
 * "db://" scheme to load textures directly from the app's database.
 */
public class AndroidAssetStreamProvider implements AssetStreamProvider {
	private static final String DB_SCHEME = "db";
	private final Context context;

	public AndroidAssetStreamProvider(@NonNull Context context) {
		this.context = context.getApplicationContext();
	}

	@NonNull
	@Override
	public InputStream openStream(@NonNull URI uri) throws IOException {
		String scheme = uri.getScheme();

		if (DB_SCHEME.equals(scheme)) {
			// Custom scheme to load from the database, e.g., "db://noise".
			String textureName = uri.getAuthority();
			if (textureName == null) {
				throw new IOException("Invalid database URI: " + uri);
			}
			byte[] textureData = Database.getInstance(context)
					.getDataSource()
					.texture
					.getTextureData(textureName);
			if (textureData == null) {
				throw new IOException("Texture not found in database: " + textureName);
			}
			return new ByteArrayInputStream(textureData);
		}

		// Handle standard Android content URIs.
		var contentResolver = context.getContentResolver();
		return Objects.requireNonNull(contentResolver.openInputStream(Uri.parse(uri.toString())),
				"Failed to open stream for URI: " + uri);
	}
}