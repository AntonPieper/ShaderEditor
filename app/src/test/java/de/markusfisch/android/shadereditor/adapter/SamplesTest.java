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
import de.markusfisch.android.shadereditor.project.ExamplesTest;
import de.markusfisch.android.shadereditor.project.ProjectPath;

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
		int count = 0;
		for (Samples.Sample sample : Samples.ALL) {
			if (sample.resId() != Samples.NO_TEXT) {
				listed.add(sample.resId());
				++count;
			}
		}
		assertEquals(count, listed.size());
		assertEquals(bundled, listed);
	}

	@Test
	public void everyExampleFileIsListedOnce() {
		Set<String> files = new HashSet<>();
		for (ProjectPath path : ExamplesTest.shaders()) {
			files.add(path.toString());
		}
		Set<String> listed = new HashSet<>();
		for (Samples.Sample sample : Samples.ALL) {
			listed.add(sample.path());
		}
		assertEquals(Samples.ALL.size(), listed.size());
		assertEquals(files, listed);
	}

	@Test
	public void samplesAreInLearningOrder() {
		assertEquals(List.of(
				"Shapes.frag",
				"Loop.frag",
				"Touch.frag",
				"Texture.frag",
				"Tilt.frag",
				"Compass.frag",
				"Window.frag",
				"Meters.frag",
				"Battery.frag",
				"Clock.frag",
				"Sound.frag",
				"Notifications.frag",
				"Back camera.frag",
				"Mirror.frag",
				"Trails.frag",
				"Game of Life/main.frag",
				"Electric Fade.frag",
				"Swirl.frag",
				"GLES 3.frag",
				"Blobs.frag",
				"Skybox.frag",
				"Bloom/main.frag",
				"Horizon.frag",
				"Ink/main.frag"), paths());
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
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.DEVICE_INPUTS,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.TECHNIQUES,
				Samples.Section.WALLPAPERS,
				Samples.Section.WALLPAPERS), sections);
	}

	private static List<String> paths() {
		List<String> paths = new ArrayList<>();
		for (Samples.Sample sample : Samples.ALL) {
			paths.add(sample.path());
		}
		return paths;
	}

	// The drawer lists the examples' folder as any folder, ordered by
	// its README: every example is linked there once, under the
	// heading of its section, in the order of the list.
	@Test
	public void theExamplesReadmeListsEveryExampleInItsSection()
			throws java.io.IOException {
		java.io.File folder = ExamplesTest.folder();
		String text = new String(java.nio.file.Files.readAllBytes(new java.io.File(
				folder, "README.md").toPath()),
				java.nio.charset.StandardCharsets.UTF_8);
		String[] names = folder.list();
		java.util.List<de.markusfisch.android.shadereditor.navigation.Readme.Section>
				sections = de.markusfisch.android.shadereditor.navigation.Readme
				.sections(text, new HashSet<>(java.util.Arrays.asList(names)));
		java.util.List<String> linked = new java.util.ArrayList<>();
		for (var section : sections.subList(0,
				Samples.Section.values().length)) {
			for (var entry : section.entries()) {
				linked.add(entry.name());
			}
		}
		java.util.List<String> expected = new java.util.ArrayList<>();
		for (Samples.Section section : Samples.Section.values()) {
			for (Samples.Sample sample : Samples.ALL) {
				if (sample.section() == section) {
					expected.add(sample.path().split("/")[0]);
				}
			}
		}
		assertEquals(expected, linked);
		assertEquals("Shared files", sections.get(sections.size() - 1)
				.heading());
	}
}
