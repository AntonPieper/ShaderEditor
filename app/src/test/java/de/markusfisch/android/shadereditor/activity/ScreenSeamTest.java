package de.markusfisch.android.shadereditor.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;

/**
 * The main screen is in one of two worlds, rows of the database or
 * files, and the activity does not know how either works: it makes
 * one screen and talks to it through {@code ShaderScreen}.
 */
public class ScreenSeamTest {
	private static final String MANAGERS =
			"import de.markusfisch.android.shadereditor.activity.managers.";
	private static final String PROJECT =
			"import de.markusfisch.android.shadereditor.project.";
	// What belongs to no world, the interface, and the two screens
	// for the one place that makes them.
	private static final Set<String> NO_WORLD = Set.of(
			"DatabaseScreen", "ExtraKeysManager", "LibraryScreen",
			"MainMenuManager", "NavigationManager", "OpenItem",
			"ShaderScreen", "ShaderViewManager", "UIManager");

	// Of the managers the activity names none that belongs to a world,
	// and of the files' layer only what a run reports.
	@Test
	public void theActivityNamesNoClassOfAWorld() throws IOException {
		Set<String> named = new TreeSet<>();
		for (String line : mainActivity().split("\n")) {
			if (line.startsWith(MANAGERS)) {
				named.add(name(line, MANAGERS));
			} else if (line.startsWith(PROJECT)) {
				assertEquals("ProjectDiagnostic", name(line, PROJECT));
			}
		}
		named.removeAll(NO_WORLD);
		assertTrue("names " + named, named.isEmpty());
	}

	// Which world a screen is in is decided in one place, once.
	@Test
	public void theWorldIsChosenOnce() throws IOException {
		String source = mainActivity();
		assertEquals(1, count(source, "project_mode"));
		// The import and the one place that makes it.
		assertEquals(2, count(source, "LibraryScreen"));
		assertEquals(1, count(source, "new LibraryScreen("));
		assertEquals(2, count(source, "DatabaseScreen"));
		assertEquals(1, count(source, "new DatabaseScreen("));
		assertFalse(source.contains("instanceof ShaderScreen"));
	}

	private static String name(String line, String prefix) {
		return line.substring(prefix.length(), line.indexOf(';'));
	}

	private static int count(String text, String part) {
		int count = 0;
		for (int i = text.indexOf(part); i >= 0;
				i = text.indexOf(part, i + 1)) {
			++count;
		}
		return count;
	}

	private static String mainActivity() throws IOException {
		return new String(Files.readAllBytes(new File(
				"src/main/java/de/markusfisch/android/shadereditor/" +
						"activity/MainActivity.java").toPath()),
				StandardCharsets.UTF_8);
	}
}
