package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.jar.JarFile;
import java.util.stream.Stream;

import de.markusfisch.android.shadereditor.activity.ClassReferences;
import de.markusfisch.android.shadereditor.activity.ClassReferences.Member;

/**
 * No class of the project package deletes, renames, cuts or writes over
 * a file by itself: the files of a session, its journals above all, are
 * changed through {@link Disk} only, where {@link Journal} decides what
 * may go. Read from the compiled classes, so a call written anywhere,
 * in any spelling, is found. The stores of other things, the user's
 * own files and what the app keeps beside them, are named here one by
 * one with what they write; a class that is not named and calls one of
 * these fails the test.
 */
public class FileSeamTest {
	private static final String PACKAGE =
			"de/markusfisch/android/shadereditor/project/";

	// What removes a file, gives it another name, cuts it, or opens it
	// to be written over.
	private static final Set<Member> FORBIDDEN = Set.of(
			new Member("java.io.File", "delete"),
			new Member("java.io.File", "deleteOnExit"),
			new Member("java.io.File", "renameTo"),
			new Member("java.nio.file.Files", "delete"),
			new Member("java.nio.file.Files", "deleteIfExists"),
			new Member("java.nio.file.Files", "move"),
			new Member("java.io.RandomAccessFile", "setLength"),
			new Member("java.nio.channels.FileChannel", "truncate"),
			new Member("java.io.FileOutputStream", "<init>"),
			new Member("java.io.FileWriter", "<init>"));

	// The classes that may, and what each writes: never a file of a
	// session's journal.
	private static final Map<String, String> OTHER_STORES = Map.of(
			"Disk$1", "the seam itself: the files of the device",
			"DirectoryProjectFiles", "the user's files in a folder of the" +
					" app's storage, changed as the user asks",
			"Library", "the library folder and the folder a redo of the" +
					" move puts aside",
			"LibraryImport", "files the user imports into the library",
			"WallpaperSnapshots", "the copy of a shader the wallpaper runs",
			"StateFile", "settings and pictures beside the user's files," +
					" each a small file replaced in one rename",
			"MoveNote", "the note of the move of the database shaders",
			"Following", "the note of renames that settings and pictures" +
					" have yet to follow",
			"Previews", "the pictures of shaders, made again from them");

	@Test
	public void onlyTheSeamChangesFilesOfASession() throws Exception {
		Map<String, Set<Member>> found = new TreeMap<>();
		List<String> classes = projectClasses();
		for (String name : classes) {
			Set<Member> calls = new TreeSet<>(
					(a, b) -> a.toString().compareTo(b.toString()));
			for (Member member : read(name).members) {
				if (FORBIDDEN.contains(member)) {
					calls.add(member);
				}
			}
			if (!calls.isEmpty()) {
				found.put(name.substring(PACKAGE.length(),
						name.length() - ".class".length()), calls);
			}
		}
		// The classes of the session itself are read, and are not
		// among those that may.
		for (String session : List.of("Journal", "Histories",
				"ProjectSessions", "FileTexts", "FileRecord",
				"OpenProject")) {
			assertTrue(session, classes.contains(PACKAGE + session +
					".class"));
			assertTrue(session, !OTHER_STORES.containsKey(session));
		}
		// What is found is what is named, no more and no less: a name
		// that is not needed anymore goes from the list.
		assertEquals(found.toString(), new TreeSet<>(OTHER_STORES.keySet()),
				found.keySet());
		// An empty reading cannot pass: the seam does all of it.
		assertTrue(found.get("Disk$1").containsAll(List.of(
				new Member("java.io.File", "delete"),
				new Member("java.io.File", "renameTo"),
				new Member("java.io.FileOutputStream", "<init>"))));
	}

	private static List<String> projectClasses() throws Exception {
		String anchor = PACKAGE + "Journal.class";
		URL url = FileSeamTest.class.getResource("/" + anchor);
		assertNotNull(anchor, url);
		List<String> names = new ArrayList<>();
		if ("jar".equals(url.getProtocol())) {
			var connection = (JarURLConnection) url.openConnection();
			connection.setUseCaches(false);
			try (JarFile jar = connection.getJarFile()) {
				jar.stream().forEach(entry -> names.add(entry.getName()));
			}
		} else {
			Path dir = Path.of(url.toURI()).getParent();
			try (Stream<Path> files = Files.walk(dir)) {
				files.forEach(file -> names.add(PACKAGE + dir.relativize(
						file).toString().replace('\\', '/')));
			}
		}
		names.removeIf(name -> !name.startsWith(PACKAGE) ||
				!name.endsWith(".class"));
		Collections.sort(names);
		return names;
	}

	private static ClassReferences read(String resource) throws IOException {
		try (InputStream in = FileSeamTest.class.getResourceAsStream(
				"/" + resource)) {
			assertNotNull(resource, in);
			return ClassReferences.read(in);
		}
	}
}
