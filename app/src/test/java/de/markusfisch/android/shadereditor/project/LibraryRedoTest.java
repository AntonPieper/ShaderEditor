package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/** Who may put the library aside, and what goes with it. */
public class LibraryRedoTest {
	private static final String MAIN = "void main() {\n" +
			"\tgl_FragColor = vec4(1.0);\n}\n";

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private final Steps steps = new Steps();

	// The redo's guard: a document with an edit is not in the journal
	// until its timer runs, so the journal cannot be what is asked.
	@Test
	public void aLibraryThatIsOpenCountsAsOpenBeforeAnythingIsJournaled()
			throws IOException {
		Library library = library();
		ProjectSessions sessions = sessions();
		String id = sessions.open(locator(library.folder()));
		LiveProjects live = new LiveProjects(sessions, steps.worker,
				steps.main, steps.timers, OpenProject.MAX_BUDGET);
		assertFalse(live.isOpen(id));
		OpenProject[] opened = {null};
		live.open(id, locator -> new SafProjects.Opened(library.files(),
				null, null), project -> opened[0] = project);
		// Open already while it is being opened.
		assertTrue(live.isOpen(id));
		steps.settle();
		OpenProject project = opened[0];
		project.load(path("Alpha.frag"), ProjectFiles.UNKNOWN, problem -> {
		});
		steps.settle();
		assertTrue(project.document(path("Alpha.frag")).replace(0, 0,
				Text.of("// unsaved\n")));
		assertTrue(project.document(path("Alpha.frag")).isModified());
		assertTrue(sessions.unsaved(id).isEmpty());
		assertTrue(live.isOpen(id));

		project.close();
		steps.settle();
		assertFalse(live.isOpen(id));
	}

	// An opening takes several turns before its session is known. It
	// counts from the moment it is asked for, so that a redo that is
	// asked for in between is refused and the opening is never
	// answered with the folder that was put aside.
	@Test
	public void anOpeningCountsFromTheMomentItIsAskedFor() {
		LiveProjects live = new LiveProjects(sessions(), steps.worker,
				steps.main, steps.timers, OpenProject.MAX_BUDGET);
		assertFalse(live.busy());
		live.begin();
		live.begin();
		assertTrue(live.busy());
		live.end();
		assertTrue(live.busy());
		live.end();
		assertFalse(live.busy());
	}

	// What was open of the library that is put aside stays with it,
	// and the new library starts with a session of its own.
	@Test
	public void theSessionOfALibraryPutAsideGoesWithIt() throws IOException {
		Library library = library();
		ProjectSessions sessions = sessions();
		Locator before = locator(library.folder());
		String old = sessions.open(before);
		assertTrue(sessions.setShown(old, path("Alpha.frag")));
		Library.Relocation relocation = (from, to) -> {
			try {
				return sessions.relocate(locator(from), locator(to));
			} catch (IOException e) {
				return false;
			}
		};

		assertNull(library.putAside(relocation));
		File aside = new File(temp.getRoot(),
				"user-files/Shaders before redo");
		assertNull(sessions.find(before));
		assertEquals(old, sessions.find(locator(aside)));
		assertEquals(path("Alpha.frag"), sessions.shown(old));
		// The new library is nobody's session yet.
		library.files();
		String fresh = sessions.open(locator(library.folder()));
		assertNotEquals(old, fresh);
		assertNull(sessions.shown(fresh));
		// Nothing to follow is no failure, also a second time.
		assertTrue(relocation.moved(new File(temp.getRoot(), "none"),
				aside));
	}

	private Library library() throws IOException {
		File userFiles = new File(temp.getRoot(), "user-files");
		File files = new File(temp.getRoot(), "files");
		userFiles.mkdirs();
		files.mkdirs();
		Library library = new Library(userFiles, files);
		File shader = new File(library.folder(), "Alpha.frag");
		shader.getParentFile().mkdirs();
		try (FileOutputStream out = new FileOutputStream(shader)) {
			out.write(MAIN.getBytes(StandardCharsets.UTF_8));
		}
		return library;
	}

	private ProjectSessions sessions() {
		return new ProjectSessions(new File(temp.getRoot(), "sessions"));
	}

	private static Locator locator(File folder) throws IOException {
		return new Locator(Kind.DIRECTORY, folder.getCanonicalPath());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
