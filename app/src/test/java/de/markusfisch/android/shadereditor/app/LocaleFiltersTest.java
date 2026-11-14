package de.markusfisch.android.shadereditor.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

public class LocaleFiltersTest {
	@Test
	public void everyTranslationIsPackaged() {
		Set<String> translated = new TreeSet<>();
		translated.add("en");
		File[] folders = new File("src/main/res").listFiles();
		assertNotNull(folders);
		for (File folder : folders) {
			String name = folder.getName();
			// values-v31 and the like are API qualifiers, not languages
			if (name.startsWith("values-") && !name.matches("values-v\\d+")) {
				translated.add(name.substring("values-".length()));
			}
		}
		assertTrue(translated.size() > 1);

		String list = System.getProperty("app.locales");
		assertNotNull(list);
		Set<String> packaged = new TreeSet<>(Arrays.asList(list.split(",")));

		assertEquals("appLocales in app/build.gradle.kts must match the"
				+ " values-* folders", translated, packaged);
	}
}
