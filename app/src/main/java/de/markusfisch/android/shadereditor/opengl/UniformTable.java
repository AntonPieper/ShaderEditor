package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.GlslType.FLOAT;
import static de.markusfisch.android.shadereditor.opengl.GlslType.INT;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC2;
import static de.markusfisch.android.shadereditor.opengl.GlslType.VEC3;

import androidx.annotation.NonNull;
import androidx.annotation.StringRes;

import java.util.List;

import de.markusfisch.android.shadereditor.R;

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
					(in, out) -> out.setFloat(in.startRandom)));

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
	private static UniformDef array(
			@NonNull String name,
			@NonNull GlslType type,
			int length,
			@StringRes int description,
			@NonNull UniformDef.Writer writer) {
		return new UniformDef(name, type, length, description, writer);
	}
}
