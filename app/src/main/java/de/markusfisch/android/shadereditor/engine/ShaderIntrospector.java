package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;

import java.util.Set;

import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;

/**
 * A service that provides metadata about compiled shaders.
 * Operations on this interface are expected to be called on the rendering thread.
 */
public interface ShaderIntrospector {
	/**
	 * Represents the metadata for a single compiled shader program.
	 */
	interface ShaderMetadata {
		/**
		 * @return The set of active uniform names found in the shader program.
		 */
		@NonNull
		Set<String> getActiveUniformNames();
	}

	/**
	 * Immediately compiles and introspects a shader asset to retrieve its metadata.
	 * <p><b>This method must be called on the GL thread.</b></p>
	 *
	 * @param asset The shader asset to introspect.
	 * @return The metadata for the compiled shader.
	 * @throws RuntimeException if shader compilation or linking fails.
	 */
	@NonNull
	ShaderMetadata introspect(@NonNull ShaderAsset asset);
}