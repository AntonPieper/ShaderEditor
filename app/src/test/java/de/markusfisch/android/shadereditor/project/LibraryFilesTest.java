package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/**
 * The library as an open project: what is kept about its files goes
 * where the files go.
 */
public class LibraryFilesTest {
	private static final String MAIN = "void main() {\n" +
			"\tgl_FragColor = vec4(1.0);\n}\n";

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private Steps steps = new Steps();

	@Test
	public void aRenamedFileTakesItsQualityAndPictureAlong()
			throws IOException {
		Library library = library();
		OpenProject project = open(library);
		List<OpenProject.Moved> moved = new ArrayList<>();

		project.rename(path("Alpha.frag"), path("Beta.frag"), moved::add);
		steps.settle();
		assertEquals(List.of(new OpenProject.Renamed(path("Alpha.frag"),
				path("Beta.frag"))), moved);
		assertEquals(.25f, library.settings().quality(path("Beta.frag")),
				0f);
		assertEquals(path("Beta.frag"), library.settings().template());
		assertArrayEquals(Pictures.png(1),
				library.previews().get(path("Beta.frag")).png());
		// The old path may be another file now: nothing is kept there.
		assertEquals(1f, library.settings().quality(path("Alpha.frag")),
				0f);
		assertNull(library.previews().get(path("Alpha.frag")));
	}

	@Test
	public void aRenamedFolderTakesWhatIsKeptOfItsFilesAlong()
			throws IOException {
		Library library = library();
		OpenProject project = open(library);
		project.rename(path("sub"), path("moved"), done -> {
		});
		steps.settle();
		assertEquals(.5f, library.settings().quality(
				path("moved/Deep.frag")), 0f);
		assertArrayEquals(Pictures.png(2), library.previews().get(
				path("moved/Deep.frag")).png());
		assertNull(library.previews().get(path("sub/Deep.frag")));
		assertEquals(1f, library.settings().quality(path("sub/Deep.frag")),
				0f);
	}

	@Test
	public void aDeletedFileTakesWhatIsKeptOfItWithIt() throws IOException {
		Library library = library();
		OpenProject project = open(library);
		project.delete(path("Alpha.frag"), Map.of(), done -> {
		});
		project.delete(path("sub"), Map.of(), done -> {
		});
		steps.settle();
		assertFalse(new File(library.folder(), "Alpha.frag").exists());
		assertNull(library.settings().template());
		assertNull(library.previews().get(path("Alpha.frag")));
		assertNull(library.previews().get(path("sub/Deep.frag")));
		// A new file of that name starts with nothing of the old one.
		write(new File(library.folder(), "Alpha.frag"), MAIN);
		assertEquals(1f, library.settings().quality(path("Alpha.frag")),
				0f);
	}

	// Storage renamed the file, and the process died before anything
	// followed: the next opening finishes it, as it does for journals.
	@Test
	public void aRenameTheProcessDiedInIsFollowedAtTheNextOpening()
			throws IOException {
		Library library = library();
		OpenProject project = open(library);
		project.rename(path("Alpha.frag"), path("Beta.frag"), done -> {
		});
		// The worker notes the move and renames; main never hears.
		assertTrue(steps.worker.step());
		assertTrue(new File(library.folder(), "Beta.frag").isFile());
		assertEquals(.25f, library.settings().quality(path("Alpha.frag")),
				0f);
		steps.worker.clear();
		steps.main.clear();
		steps.later.clear();

		steps = new Steps();
		open(library);
		assertEquals(.25f, library.settings().quality(path("Beta.frag")),
				0f);
		assertEquals(path("Beta.frag"), library.settings().template());
		assertArrayEquals(Pictures.png(1),
				library.previews().get(path("Beta.frag")).png());
		assertNull(library.previews().get(path("Alpha.frag")));
	}

	private OpenProject open(Library library) throws IOException {
		ProjectSessions sessions = sessions();
		String id = sessions.open(locator(library.folder()));
		OpenProject project = OpenProject.open(id, library.files(), null,
				null, sessions, steps.worker, steps.main, steps.timers,
				OpenProject.MAX_BUDGET, library.kept());
		assertNotNull(project);
		steps.settle();
		return project;
	}

	// A library with a shader in its root that is the template, and
	// one in a folder, each with a quality and a picture.
	private Library library() throws IOException {
		File userFiles = new File(temp.getRoot(), "user-files");
		File files = new File(temp.getRoot(), "files");
		if (new File(userFiles, Library.FOLDER).exists()) {
			return new Library(userFiles, files);
		}
		userFiles.mkdirs();
		files.mkdirs();
		Library library = new Library(userFiles, files);
		write(new File(library.folder(), "Alpha.frag"), MAIN);
		write(new File(library.folder(), "sub/Deep.frag"), MAIN);
		assertTrue(library.settings().setTemplate(path("Alpha.frag")));
		assertTrue(library.settings().setQuality(path("Alpha.frag"), .25f));
		assertTrue(library.settings().setQuality(path("sub/Deep.frag"),
				.5f));
		assertTrue(library.previews().put(path("Alpha.frag"), "r1",
				Pictures.png(1)));
		assertTrue(library.previews().put(path("sub/Deep.frag"), "r2",
				Pictures.png(2)));
		return library;
	}

	private ProjectSessions sessions() {
		return new ProjectSessions(new File(temp.getRoot(), "sessions"));
	}

	private static Locator locator(File folder) throws IOException {
		return new Locator(Kind.DIRECTORY, folder.getCanonicalPath());
	}

	private static void write(File file, String text) throws IOException {
		file.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
