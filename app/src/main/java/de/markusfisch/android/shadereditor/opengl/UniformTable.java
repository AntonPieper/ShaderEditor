package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.GlslType.FLOAT;
import static de.markusfisch.android.shadereditor.opengl.GlslType.INT;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC2;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC3;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC4;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
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
					}));

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
	private static UniformDef array(
			@NonNull String name,
			@NonNull GlslType type,
			int length,
			@StringRes int description,
			@NonNull UniformDef.Writer writer) {
		return new UniformDef(name, type, length, description, writer);
	}
}
