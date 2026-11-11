package de.markusfisch.android.shadereditor.adapter;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.RawRes;
import androidx.annotation.StringRes;

import java.util.List;

import de.markusfisch.android.shadereditor.R;

public final class Samples {
	public enum Section {
		START_HERE(R.string.samples_section_start_here),
		DEVICE_INPUTS(R.string.samples_section_device_inputs),
		TECHNIQUES(R.string.samples_section_techniques),
		WALLPAPERS(R.string.samples_section_wallpapers);

		@StringRes
		public final int titleId;

		Section(@StringRes int titleId) {
			this.titleId = titleId;
		}
	}

	public record Sample(
			@NonNull Section section,
			@RawRes int resId,
			@DrawableRes int thumbId,
			@StringRes int nameId,
			@StringRes int rationaleId,
			float quality) {
	}

	// Within a section, samples are listed in this order.
	public static final List<Sample> ALL = List.of(
			new Sample(Section.START_HERE,
					R.raw.sample_shapes,
					R.drawable.thumbnail_shapes,
					R.string.sample_shapes_name,
					R.string.sample_shapes_rationale,
					1f),
			new Sample(Section.START_HERE,
					R.raw.sample_loop,
					R.drawable.thumbnail_loop,
					R.string.sample_loop_name,
					R.string.sample_loop_rationale,
					1f),
			new Sample(Section.START_HERE,
					R.raw.sample_touch,
					R.drawable.thumbnail_touch,
					R.string.sample_touch_name,
					R.string.sample_touch_rationale,
					1f),
			new Sample(Section.START_HERE,
					R.raw.sample_texture,
					R.drawable.thumbnail_texture,
					R.string.sample_texture_name,
					R.string.sample_texture_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_tilt,
					R.drawable.thumbnail_tilt,
					R.string.sample_tilt_name,
					R.string.sample_tilt_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_compass,
					R.drawable.thumbnail_compass,
					R.string.sample_compass_name,
					R.string.sample_compass_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_window,
					R.drawable.thumbnail_window,
					R.string.sample_window_name,
					R.string.sample_window_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_meters,
					R.drawable.thumbnail_meters,
					R.string.sample_meters_name,
					R.string.sample_meters_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_battery,
					R.drawable.thumbnail_battery,
					R.string.sample_battery_name,
					R.string.sample_battery_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_clock,
					R.drawable.thumbnail_clock,
					R.string.sample_clock_name,
					R.string.sample_clock_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_sound,
					R.drawable.thumbnail_sound,
					R.string.sample_sound_name,
					R.string.sample_sound_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_notifications,
					R.drawable.thumbnail_notifications,
					R.string.sample_notifications_name,
					R.string.sample_notifications_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_camera_back,
					R.drawable.thumbnail_camera_back,
					R.string.sample_camera_back_name,
					R.string.sample_camera_back_rationale,
					1f),
			new Sample(Section.DEVICE_INPUTS,
					R.raw.sample_mirror,
					R.drawable.thumbnail_mirror,
					R.string.sample_mirror_name,
					R.string.sample_mirror_rationale,
					1f),
			new Sample(Section.TECHNIQUES,
					R.raw.sample_trails,
					R.drawable.thumbnail_trails,
					R.string.sample_trails_name,
					R.string.sample_trails_rationale,
					1f),
			new Sample(Section.TECHNIQUES,
					R.raw.sample_game_of_life,
					R.drawable.thumbnail_game_of_life,
					R.string.sample_game_of_life_name,
					R.string.sample_game_of_life_rationale,
					.125f),
			new Sample(Section.TECHNIQUES,
					R.raw.sample_electric_fade,
					R.drawable.thumbnail_electric_fade,
					R.string.sample_electric_fade_name,
					R.string.sample_electric_fade_rationale,
					.125f),
			new Sample(Section.TECHNIQUES,
					R.raw.sample_swirl,
					R.drawable.thumbnail_swirl,
					R.string.sample_swirl_name,
					R.string.sample_swirl_rationale,
					1f),
			new Sample(Section.TECHNIQUES,
					R.raw.sample_gles_300,
					R.drawable.thumbnail_gles_300,
					R.string.sample_gles_300_name,
					R.string.sample_gles_300_rationale,
					1f));

	private Samples() {
	}
}
