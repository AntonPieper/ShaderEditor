package de.markusfisch.android.shadereditor.platform.data;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import org.jetbrains.annotations.Contract;

import de.markusfisch.android.shadereditor.engine.data.DataKey;
import de.markusfisch.android.shadereditor.engine.scene.UniformBinding;


/**
 * Represents a comprehensive definition for a shader uniform.
 * <p>
 * This class encapsulates all the information needed to define and use a uniform,
 * including its binding logic and associated metadata for display and documentation.
 * It serves as a comprehensive description of a uniform that can be used by both
 * the shader engine and the user interface.
 *
 * @param <T>             The type of data this uniform binding handles.
 * @param binding         The {@link UniformBinding} which defines how the uniform gets its data.
 * @param displayMetadata The {@link DisplayMetadata} associated with this uniform, containing
 *                        information like its GLSL type and user-facing description.
 */
public record UniformDefinition<T>(
		@NonNull UniformBinding<T> binding,
		@NonNull DisplayMetadata displayMetadata
) {
	/**
	 * Creates a new {@link UniformDefinition} instance.
	 * <p>
	 * This record contains everything needed to both implement the uniform in the
	 * game plugin and display it in the editor UI. It's the contract that the
	 * platform provides to the rest of the application.
	 *
	 * @param uniformName      The suggested GLSL uniform name (e.g., "time").
	 * @param key              The unique, type-safe key for the data source.
	 * @param mapper           The function to map the data from the key to a Uniform.
	 * @param glslType         The GLSL type string for the uniform (e.g., "float", "vec2").
	 * @param descriptionResId The string resource ID for the user-facing description.
	 * @param <T>              The type of data the binding uses.
	 * @return A new {@link UniformDefinition} instance.
	 */
	@NonNull
	@Contract("_, _, _, _, _ -> new")
	public static <T> UniformDefinition<T> from(
			@NonNull String uniformName,
			@NonNull DataKey<T> key,
			@NonNull UniformBinding.Mapper<T> mapper,
			@NonNull String glslType,
			@StringRes int descriptionResId
	) {
		return new UniformDefinition<>(
				new UniformBinding<>(uniformName, key, mapper),
				new DisplayMetadata(uniformName, glslType, descriptionResId)
		);
	}


	/**
	 * Metadata for a uniform, primarily for UI display purposes.
	 *
	 * @param glslType         The GLSL type string for the uniform (e.g., "float", "vec2").
	 * @param descriptionResId The string resource ID for the user-facing description.
	 */
	public record DisplayMetadata(
			@NonNull String uniformName,
			@NonNull String glslType,
			@StringRes int descriptionResId
	) {
	}
}