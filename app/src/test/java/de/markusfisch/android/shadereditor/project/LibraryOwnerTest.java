package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The library's folder has one owner, this layer: a screen cannot
 * get at the folder, so it cannot list or write it under rules of
 * its own. It reads through the library's readers.
 */
public class LibraryOwnerTest {
	private static final Path MAIN = new File(
			"src/main/java/de/markusfisch/android/shadereditor").toPath();

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// Nothing outside the layer is handed the folder, or the files of
	// the folder with their ways to write.
	@Test
	public void onlyTheLayerHasTheLibrarysFolder()
			throws NoSuchMethodException {
		assertFalse(Modifier.isPublic(Library.class
				.getDeclaredMethod("folder").getModifiers()));
		assertFalse(Modifier.isPublic(Library.class
				.getDeclaredMethod("files").getModifiers()));
	}

	// Nor does anything outside it make its own access to a folder of
	// files, or name the folder other apps see, which the library is
	// in: only the provider that shows that folder to them does.
	@Test
	public void noScreenReachesIntoStorageItself() throws IOException {
		List<String> reaching = new ArrayList<>();
		try (Stream<Path> files = Files.walk(MAIN)) {
			for (Path file : files.filter(path -> path.toString()
					.endsWith(".java")).collect(Collectors.toList())) {
				String name = MAIN.relativize(file).toString()
						.replace(File.separatorChar, '/');
				if (name.startsWith("project/")) {
					continue;
				}
				String source = new String(Files.readAllBytes(file),
						StandardCharsets.UTF_8);
				boolean provider = name.equals(
						"provider/UserFilesProvider.java");
				if (source.contains("new DirectoryProjectFiles(") ||
						!provider && (source.contains("\"user-files\"") ||
								source.contains("getUserFilesDir("))) {
					reaching.add(name);
				}
			}
		}
		assertTrue("reach into storage: " + reaching, reaching.isEmpty());
	}

	// What the lists outside the drawer count and offer: the files of
	// the root, last changed first, without folders and hidden files.
	@Test
	public void theRootsFilesAreListedLastChangedFirst() throws IOException {
		Library library = new Library(temp.newFolder("user-files"),
				temp.newFolder("files"));
		assertTrue(library.rootFiles().isEmpty());

		File folder = library.folder();
		assertTrue(folder.mkdirs());
		write(new File(folder, "Old.frag"), 1000);
		write(new File(folder, "New.frag"), 3000);
		write(new File(folder, "Mid.frag"), 2000);
		write(new File(folder, ".hidden"), 4000);
		assertTrue(new File(folder, "textures").mkdir());

		List<String> names = new ArrayList<>();
		for (Library.RootFile file : library.rootFiles()) {
			names.add(file.name() + " " + file.modified());
		}
		assertEquals(List.of("New.frag 3000", "Mid.frag 2000",
				"Old.frag 1000"), names);
	}

	// A start shows the newest file where the one shown last is gone:
	// of the files, not of the folders.
	@Test
	public void theNewestOfAFolderIsAFile() {
		ProjectPath old = ProjectPath.parse("Old.frag").value();
		ProjectPath newer = ProjectPath.parse("New.frag").value();
		ProjectPath folder = ProjectPath.parse("textures").value();

		assertEquals(newer, Library.newest(new OpenProject.Folder(List.of(
				new OpenProject.Item(old, false, 1, 1000),
				new OpenProject.Item(folder, true, 0, 9000),
				new OpenProject.Item(newer, false, 1, 3000)), null)));
		assertNull(Library.newest(new OpenProject.Folder(List.of(
				new OpenProject.Item(folder, true, 0, 9000)), null)));
	}

	private static void write(File file, long modified) throws IOException {
		Files.write(file.toPath(), "void main() {}\n".getBytes(
				StandardCharsets.UTF_8));
		assertTrue(file.setLastModified(modified));
	}
}
