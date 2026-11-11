package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.GlslType.FLOAT;
import static de.markusfisch.android.shadereditor.opengl.GlslType.INT;
import static de.markusfisch.android.shadereditor.opengl.GlslType.MAT2;
import static de.markusfisch.android.shadereditor.opengl.GlslType.MAT3;
import static de.markusfisch.android.shadereditor.opengl.GlslType.SAMPLER_2D;
import static de.markusfisch.android.shadereditor.opengl.GlslType.SAMPLER_EXTERNAL_OES;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC2;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC3;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC4;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Gravity;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Gyroscope;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Light;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Linear;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Magnetic;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Pressure;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Proximity;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Rotation;
import de.markusfisch.android.shadereditor.opengl.SensorSources.RotationVector;
import de.markusfisch.android.shadereditor.opengl.SystemSources.Battery;
import de.markusfisch.android.shadereditor.opengl.SystemSources.Clock;
import de.markusfisch.android.shadereditor.opengl.SystemSources.MediaVolume;
import de.markusfisch.android.shadereditor.opengl.SystemSources.Microphone;
import de.markusfisch.android.shadereditor.opengl.SystemSources.NightMode;
import de.markusfisch.android.shadereditor.opengl.SystemSources.Notifications;
import de.markusfisch.android.shadereditor.opengl.SystemSources.PowerConnected;

public final class UniformTable {
	public static final List<UniformDef> BUILTINS = List.of(
			uniform("time", FLOAT, R.string.time_in_seconds_since_load,
					(in, out) -> out.setFloat(in.time)),
			uniform("second", INT, R.string.int_seconds_since_load,
					(in, out) -> out.setInt((int) in.time)),
			uniform("subsecond", FLOAT, R.string.fractional_part_of_seconds_since_load,
					(in, out) -> out.setFloat(in.time - (int) in.time)),
			uniform("frame", INT, R.string.frame_number,
					(in, out) -> out.setInt(in.frame)),
			uniform("ftime", FLOAT, R.string.time_in_cycle,
					(in, out) -> out.setFloat((in.time % in.fTimeMax) / in.fTimeMax * 2f - 1f)),
			uniform("resolution", VEC2, R.string.resolution_in_pixels,
					(in, out) -> out.setVec2(in.width, in.height)),
			uniform("touch", VEC2, R.string.touch_position_in_pixels,
					(in, out) -> out.setVec2(in.touchX, in.touchY)),
			uniform("touchStart", VEC2, R.string.touch_start_position_in_pixels,
					(in, out) -> out.setVec2(in.touchStartX, in.touchStartY)),
			uniform("mouse", VEC2, R.string.normalized_touch_position,
					(in, out) -> out.setVec2(in.mouseX, in.mouseY)),
			uniform("pointerCount", INT, R.string.number_of_touches,
					(in, out) -> out.setInt(in.pointerCount)),
			array("pointers", VEC3, FrameInputs.MAX_POINTERS, R.string.positions_of_touches,
					(in, out) -> {
						if (in.pointerCount > 0) {
							out.setVec3Array(in.pointers, in.pointerCount);
						}
					}),
			uniform("offset", VEC2, R.string.wallpaper_offset,
					(in, out) -> out.setVec2(in.offsetX, in.offsetY)),
			uniform("startRandom", FLOAT, R.string.start_random,
					(in, out) -> out.setFloat(in.startRandom)),
			uniform("gravity", VEC3, R.string.gravity_vector, Gravity.KEY,
					(gravity, out) -> setVec3(out, gravity.values)),
			uniform("linear", VEC3, R.string.linear_acceleration_vector, Linear.KEY,
					(linear, out) -> setVec3(out, linear.values)),
			uniform("gyroscope", VEC3, R.string.gyroscope, Gyroscope.KEY,
					(gyroscope, out) -> setVec3(out, gyroscope.values)),
			uniform("magnetic", VEC3, R.string.magnetic_field, Magnetic.KEY,
					(magnetic, out) -> setVec3(out, magnetic.values)),
			uniform("light", FLOAT, R.string.light, Light.KEY,
					(light, out) -> {
						if (light.registered) {
							out.setFloat(light.ambient);
						}
					}),
			uniform("pressure", FLOAT, R.string.pressure, Pressure.KEY,
					(pressure, out) -> {
						if (pressure.registered) {
							out.setFloat(pressure.pressure);
						}
					}),
			uniform("proximity", FLOAT, R.string.proximity, Proximity.KEY,
					(proximity, out) -> {
						if (proximity.registered) {
							out.setFloat(proximity.centimeters);
						}
					}),
			uniform("rotationVector", VEC3, R.string.device_rotation_vector,
					RotationVector.KEY,
					(vector, out) -> setVec3(out, vector.values)),
			uniform("rotationMatrix", MAT3, R.string.device_rotation_matrix, Rotation.KEY,
					(rotation, out) -> {
						if (rotation.available) {
							out.setMat3(true, rotation.matrix);
						}
					}),
			uniform("orientation", VEC3, R.string.device_orientation, Rotation.KEY,
					(rotation, out) -> {
						if (rotation.available) {
							setVec3(out, rotation.getOrientation());
						}
					}),
			uniform("inclinationMatrix", MAT3, R.string.device_inclination_matrix,
					Rotation.KEY,
					(rotation, out) -> {
						if (rotation.inclined) {
							out.setMat3(true, rotation.inclinationMatrix);
						}
					}),
			uniform("inclination", FLOAT, R.string.device_inclination, Rotation.KEY,
					(rotation, out) -> {
						if (rotation.inclined) {
							out.setFloat(rotation.getInclination());
						}
					}),
			uniform("nightMode", INT, R.string.night_mode, NightMode.KEY,
					(nightMode, out) -> out.setInt(nightMode.night ? 1 : 0)),
			uniform("notificationCount", INT, R.string.notification_count, Notifications.KEY,
					(notifications, out) -> out.setInt(notifications.count)),
			uniform("lastNotificationTime", FLOAT, R.string.last_notification_time,
					Notifications.KEY,
					(notifications, out) -> out.setFloat(notifications.secondsSinceLast)),
			uniform("battery", FLOAT, R.string.battery_level, Battery.KEY,
					(battery, out) -> out.setFloat(battery.level)),
			uniform("powerConnected", INT, R.string.power_connected, PowerConnected.KEY,
					(power, out) -> out.setInt(power.connected ? 1 : 0)),
			uniform("date", VEC4, R.string.date_time, Clock.KEY,
					(clock, out) -> out.setVec4(
							clock.year, clock.month, clock.day, clock.secondOfDay)),
			uniform("daytime", VEC3, R.string.daytime, Clock.KEY,
					(clock, out) -> out.setVec3(clock.hour, clock.minute, clock.second)),
			uniform("mediaVolume", FLOAT, R.string.media_volume_level, MediaVolume.KEY,
					(volume, out) -> out.setFloat(volume.level)),
			uniform("micAmplitude", FLOAT, R.string.microphone_amplitude, Microphone.KEY,
					(mic, out) -> {
						if (mic.recording) {
							out.setFloat(mic.amplitude);
						}
					}),
			uniform("cameraOrientation", MAT2, R.string.camera_orientation, CameraSource.KEY,
					(camera, out) -> {
						if (camera.open) {
							out.setMat2(false, camera.orientation);
						}
					}),
			uniform("cameraAddent", VEC2, R.string.camera_addent, CameraSource.KEY,
					(camera, out) -> {
						if (camera.open) {
							out.setVec2(camera.addent[0], camera.addent[1]);
						}
					}),
			declaration(ShaderRenderer.UNIFORM_BACKBUFFER, SAMPLER_2D,
					R.string.previous_frame, null),
			declaration(ShaderRenderer.UNIFORM_CAMERA_BACK, SAMPLER_EXTERNAL_OES,
					R.string.camera_back, CameraSource.KEY),
			declaration(ShaderRenderer.UNIFORM_CAMERA_FRONT, SAMPLER_EXTERNAL_OES,
					R.string.camera_front, CameraSource.KEY));

	private UniformTable() {
	}

	@NonNull
	public static UniformDef get(@NonNull String name) {
		for (var uniform : BUILTINS) {
			if (uniform.getName().equals(name)) {
				return uniform;
			}
		}
		throw new IllegalArgumentException("No builtin uniform " + name);
	}

	private static void setVec3(@NonNull UniformOutput out, @Nullable float[] values) {
		if (values != null) {
			out.setVec3(values[0], values[1], values[2]);
		}
	}

	@NonNull
	private static UniformDef uniform(
			@NonNull String name,
			@NonNull GlslType type,
			@StringRes int description,
			@NonNull UniformDef.Writer writer) {
		return new UniformDef(name, type, 0, description, writer);
	}

	@NonNull
	private static <S extends UniformSource> UniformDef uniform(
			@NonNull String name,
			@NonNull GlslType type,
			@StringRes int description,
			@NonNull UniformSource.Key<S> source,
			@NonNull UniformDef.SourceWriter<S> writer) {
		return new UniformDef(name, type, 0, description, source, writer);
	}

	@NonNull
	private static UniformDef declaration(
			@NonNull String name,
			@NonNull GlslType type,
			@StringRes int description,
			@Nullable UniformSource.Key<?> source) {
		return new UniformDef(name, type, description, source);
	}

	@NonNull
	private static UniformDef array(
			@NonNull String name,
			@NonNull GlslType type,
			int length,
			@StringRes int description,
			@NonNull UniformDef.Writer writer) {
		return new UniformDef(name, type, length, description, writer);
	}
}
