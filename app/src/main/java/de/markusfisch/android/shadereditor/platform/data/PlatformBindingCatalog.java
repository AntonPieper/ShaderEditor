package de.markusfisch.android.shadereditor.platform.data;

import java.util.List;
import java.util.stream.Collectors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.data.EngineDataKeys;
import de.markusfisch.android.shadereditor.engine.data.InteractionDataKeys;
import de.markusfisch.android.shadereditor.engine.data.SensorDataKeys;
import de.markusfisch.android.shadereditor.engine.data.SystemDataKeys;
import de.markusfisch.android.shadereditor.engine.scene.Uniform;
import de.markusfisch.android.shadereditor.engine.scene.UniformBinding;

/**
 * Provides the static, canonical list of all default uniform bindings
 * supported by the platform. This is the single source of truth for both
 * the engine's default configuration and the editor's UI.
 */
public final class PlatformBindingCatalog {
	private static final List<UniformDefinition<?>> BINDINGS = List.of(
			UniformDefinition.from(
					"resolution",
					EngineDataKeys.VIEWPORT_RESOLUTION,
					v -> new Uniform.FloatVec2(Viewport.toVec2(v)),
					"vec2",
					R.string.resolution_in_pixels
			),
			UniformDefinition.from(
					"time",
					SystemDataKeys.TIME,
					Uniform.FloatScalar::new,
					"float",
					R.string.time_in_seconds_since_load
			),
			UniformDefinition.from(
					"nightMode",
					SystemDataKeys.IS_NIGHT_MODE,
					v -> new Uniform.IntScalar(v ? 1 : 0),
					"bool",
					R.string.night_mode
			),
			UniformDefinition.from(
					"batteryLevel",
					SystemDataKeys.BATTERY_LEVEL,
					v -> new Uniform.FloatScalar(new float[]{v}),
					"float",
					R.string.battery_level
			),
			UniformDefinition.from(
					"powerConnected",
					SystemDataKeys.POWER_CONNECTED,
					v -> new Uniform.IntScalar(new int[]{v ? 1 : 0}),
					"bool",
					R.string.power_connected
			),
			UniformDefinition.from(
					"isCharging",
					SystemDataKeys.BATTERY_IS_CHARGING,
					v -> new Uniform.IntScalar(new int[]{v ? 1 : 0}),
					"bool",
					R.string.is_charging
			),
			UniformDefinition.from(
					"touch",
					InteractionDataKeys.TOUCH_POSITION,
					v -> new Uniform.FloatVec2(v.x(), v.y()),
					"vec2",
					R.string.touch_position_in_pixels
			),
			UniformDefinition.from(
					"offset",
					InteractionDataKeys.WALLPAPER_OFFSET,
					v -> new Uniform.FloatVec2(v.x(), v.y()),
					"vec2",
					R.string.wallpaper_offset
			),
			UniformDefinition.from(
					"rotationMatrix",
					SensorDataKeys.ROTATION_MATRIX,
					Uniform.FloatMat4::new,
					"mat4",
					R.string.device_rotation_matrix
			),
			UniformDefinition.from(
					"gravity",
					SensorDataKeys.GRAVITY,
					Uniform.FloatVec3::new,
					"vec3",
					R.string.gravity_vector
			),
			UniformDefinition.from(
					"geomagnetic",
					SensorDataKeys.GEOMAGNETIC,
					Uniform.FloatVec3::new,
					"vec3",
					R.string.magnetic_field
			),
			UniformDefinition.from(
					"inclinationMatrix",
					SensorDataKeys.INCLINATION_MATRIX,
					Uniform.FloatMat4::new,
					"mat4",
					R.string.device_inclination_matrix
			)
	);

	/**
	 * Gets the complete list of declarative bindings.
	 *
	 * @return The canonical, unmodifiable list of bindings with all metadata.
	 */
	public static List<UniformDefinition.DisplayMetadata> getMetadata() {
		return BINDINGS.stream().map(UniformDefinition::displayMetadata).toList();
	}

	/**
	 * Gets a pure, abstracted list of UniformBindings suitable for the game engine,
	 * stripped of any platform-specific metadata.
	 *
	 * @return A new list of {@link UniformBinding} objects.
	 */
	public static List<UniformBinding<?>> getUniformBindings() {
		return BINDINGS.stream()
				.map(UniformDefinition::binding)
				.collect(Collectors.toList());
	}
}