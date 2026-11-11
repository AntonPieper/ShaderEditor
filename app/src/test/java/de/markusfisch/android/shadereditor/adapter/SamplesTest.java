package de.markusfisch.android.shadereditor.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;

public class SamplesTest {
	@Test
	public void everySampleHasASection() {
		for (Samples.Sample sample : Samples.ALL) {
			assertNotNull(sample.section());
		}
	}

	@Test
	public void everyBundledSampleIsListedOnce() throws IllegalAccessException {
		Set<Integer> bundled = new HashSet<>();
		for (Field field : R.raw.class.getFields()) {
			if (field.getName().startsWith("sample_")) {
				bundled.add(field.getInt(null));
			}
		}
		Set<Integer> listed = new HashSet<>();
		for (Samples.Sample sample : Samples.ALL) {
			listed.add(sample.resId());
		}
		assertEquals(Samples.ALL.size(), listed.size());
		assertEquals(bundled, listed);
	}

	@Test
	public void samplesAreInLearningOrder() {
		assertEquals(List.of(
				R.raw.sample_shapes,
				R.raw.sample_loop,
				R.raw.sample_touch,
				R.raw.sample_texture,
				R.raw.sample_tilt,
				R.raw.sample_compass,
				R.raw.sample_window,
				R.raw.sample_battery,
				R.raw.sample_camera_back,
				R.raw.sample_game_of_life,
				R.raw.sample_electric_fade,
				R.raw.sample_swirl,
				R.raw.sample_gles_300,
				R.raw.sample_cloudy_conway), resIds());
	}

	@Test
	public void samplesAreInTheirSections() {
		List<Samples.Section> sections = new ArrayList<>();
		for (Samples.Sample sample : Samples.ALL) {
			sections.add(sample.section());
		}
		assertEquals(List.of(
				Samples.Section.START_HERE,
				Samples.Section.START_HERE,
				Samples.Section.START_HERE,
				Samples.Section.START_HERE,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES), sections);
	}

	private static List<Integer> resIds() {
		List<Integer> ids = new ArrayList<>();
		for (Samples.Sample sample : Samples.ALL) {
			ids.add(sample.resId());
		}
		return ids;
	}
}
