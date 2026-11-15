package de.markusfisch.android.shadereditor.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import de.markusfisch.android.shadereditor.activity.managers.LibraryScreen;

/**
 * The main screen is in one of two worlds, rows of the database or
 * files, and the activity does not know how either works: it makes
 * one screen and talks to it through {@code ShaderScreen}.
 */
public class ScreenSeamTest {
	private static final String MANAGERS =
			"de.markusfisch.android.shadereditor.activity.managers.";
	private static final String PROJECT =
			"de.markusfisch.android.shadereditor.project.";
	private static final String ACTIVITY =
			"de/markusfisch/android/shadereditor/activity/MainActivity";
	// What belongs to no world, and the interface.
	private static final Set<String> NO_WORLD = Set.of(
			"ExtraKeysManager", "MainMenuManager", "NavigationManager",
			"OpenItem", "ShaderScreen", "ShaderViewManager", "UIManager");
	// Made in the one place that chooses the world, and only made.
	private static final Set<String> SCREENS = Set.of(
			MANAGERS + "DatabaseScreen", MANAGERS + "LibraryScreen");

	// The review's bypass: the library named in full, without an import.
	// Compiled only, never run.
	@SuppressWarnings("unused")
	static final class FullyQualifiedBypass {
		void bypassScreen(Context context) {
			new de.markusfisch.android.shadereditor.project.Projects(context)
					.library();
		}
	}

	// A screen used for more than being made. Compiled only, never run.
	@SuppressWarnings("unused")
	static final class ScreenReachedInto {
		void reachIn(LibraryScreen screen) {
			screen.onStart();
		}
	}

	// What the compiled activity and its nested classes refer to, also
	// written out in full: of the managers none that belongs to a world,
	// of the files' layer only what a run reports, and the two screens
	// only to make them.
	@Test
	public void theCompiledActivityRefersToNoClassOfAWorld()
			throws Exception {
		List<String> classes = activityClasses();
		assertTrue(classes.toString(), classes.contains(ACTIVITY + ".class"));
		for (String name : classes) {
			assertEquals(name, List.of(), crossings(read("/" + name)));
		}
		// What it must refer to, so that an empty reading cannot pass:
		// the interface, and both screens made.
		var activity = read("/" + ACTIVITY + ".class");
		assertTrue(activity.classes.contains(MANAGERS + "ShaderScreen"));
		for (String screen : SCREENS) {
			assertTrue(screen, activity.members.contains(
					new ClassReferences.Member(screen, "<init>")));
		}
	}

	// The same check rejects what the import scan before it let pass.
	@Test
	public void aFullyQualifiedUseOfTheLibraryIsRejected() throws IOException {
		assertEquals(List.of(
						PROJECT + "Library",
						PROJECT + "Projects"),
				crossings(read(nested(FullyQualifiedBypass.class))));
	}

	@Test
	public void aScreenUsedBeyondBeingMadeIsRejected() throws IOException {
		assertEquals(List.of(
						MANAGERS + "LibraryScreen in a signature",
						MANAGERS + "LibraryScreen.onStart"),
				crossings(read(nested(ScreenReachedInto.class))));
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

	// The library's session is known in one place, Projects: nothing
	// that works on files is handed a copy of it to keep in step.
	@Test
	public void noManagerKeepsTheLibrarysSession() throws IOException {
		for (String manager : new String[]{"ProjectFileManager",
				"LibraryLooks", "NewFile", "LibraryAdd", "LibraryStart"}) {
			assertFalse(manager, source("activity/managers/" + manager +
					".java").contains("void setLibrary("));
		}
	}

	// What of a world the class refers to, sorted; empty when nothing.
	private static List<String> crossings(ClassReferences references) {
		List<String> crossings = new ArrayList<>();
		Set<String> types = new java.util.TreeSet<>(references.classes);
		types.addAll(references.typesInSignatures);
		for (String type : types) {
			String outer = type.contains("$")
					? type.substring(0, type.indexOf('$'))
					: type;
			if (outer.startsWith(MANAGERS) && !SCREENS.contains(outer) &&
					!NO_WORLD.contains(outer.substring(MANAGERS.length()))) {
				crossings.add(type);
			} else if (outer.startsWith(PROJECT) &&
					!outer.equals(PROJECT + "ProjectDiagnostic")) {
				crossings.add(type);
			}
		}
		for (var member : references.members) {
			if (SCREENS.contains(member.owner()) &&
					!member.name().equals("<init>")) {
				crossings.add(member.owner() + "." + member.name());
			}
		}
		// No field, parameter or result of a screen's type: it is kept as
		// a ShaderScreen.
		for (String type : references.typesInSignatures) {
			if (SCREENS.contains(type)) {
				crossings.add(type + " in a signature");
			}
		}
		Collections.sort(crossings);
		return crossings;
	}

	// The class files of the activity and of its nested classes.
	private static List<String> activityClasses() throws Exception {
		URL url = ScreenSeamTest.class.getResource("/" + ACTIVITY + ".class");
		assertTrue("no " + ACTIVITY, url != null);
		String folder = ACTIVITY.substring(0, ACTIVITY.lastIndexOf('/') + 1);
		List<String> names = new ArrayList<>();
		if ("jar".equals(url.getProtocol())) {
			var connection = (JarURLConnection) url.openConnection();
			connection.setUseCaches(false);
			try (JarFile jar = connection.getJarFile()) {
				jar.stream().forEach(entry -> names.add(entry.getName()));
			}
		} else {
			try (Stream<Path> files = Files.list(
					Path.of(url.toURI()).getParent())) {
				files.forEach(file -> names.add(folder + file.getFileName()));
			}
		}
		names.removeIf(name -> !name.equals(ACTIVITY + ".class") &&
				!name.startsWith(ACTIVITY + "$"));
		Collections.sort(names);
		return names;
	}

	private static String nested(Class<?> type) {
		return "/" + type.getName().replace('.', '/') + ".class";
	}

	private static ClassReferences read(String resource) throws IOException {
		try (InputStream in = ScreenSeamTest.class.getResourceAsStream(
				resource)) {
			assertTrue("no " + resource, in != null);
			return ClassReferences.read(in);
		}
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
		return source("activity/MainActivity.java");
	}

	private static String source(String path) throws IOException {
		return new String(Files.readAllBytes(new File(
				"src/main/java/de/markusfisch/android/shadereditor/" +
						path).toPath()), StandardCharsets.UTF_8);
	}
}
