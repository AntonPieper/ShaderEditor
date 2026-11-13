package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;
import de.markusfisch.android.shadereditor.project.EditingSession.Conflict;
import de.markusfisch.android.shadereditor.project.EditingSession.Failed;
import de.markusfisch.android.shadereditor.project.EditingSession.Outcome;
import de.markusfisch.android.shadereditor.project.EditingSession.Saved;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;

public class EditingSessionTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File root;
	private File journalDir;

	@Before
	public void setUp() throws IOException {
		root = temp.newFolder("project");
		journalDir = new File(temp.getRoot(), "drafts");
	}

	@Test
	public void savesDraftsInPlace() throws IOException {
		write("main.glsl", "stored");
		DirectoryProjectFiles store = new DirectoryProjectFiles(root);
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "edited",
				revision(store, "main.glsl"));
		session.setDraft(path("lib/new.glsl"), "fresh", null);
		assertEquals(Set.of(path("main.glsl"), path("lib/new.glsl")),
				session.unsaved());

		Map<ProjectPath, Outcome> outcomes = session.save();

		assertEquals(new Saved(revision(store, "main.glsl")),
				outcomes.get(path("main.glsl")));
		assertEquals(new Saved(revision(store, "lib/new.glsl")),
				outcomes.get(path("lib/new.glsl")));
		assertEquals("edited", read("main.glsl"));
		assertEquals("fresh", read("lib/new.glsl"));
		assertTrue(session.unsaved().isEmpty());
		assertTrue(new DraftJournal(journalDir).drafts().isEmpty());
	}

	@Test
	public void keepsFailedFilesForRetry() {
		MemoryFiles store = new MemoryFiles()
				.put("a.glsl", "a").put("b.glsl", "b").put("c.glsl", "c");
		store.failWrite = 2;
		EditingSession session = open(store);
		for (String name : List.of("a", "b", "c")) {
			session.setDraft(path(name + ".glsl"), name + "!",
					store.revision(name + ".glsl"));
		}

		Map<ProjectPath, Outcome> outcomes = session.save();

		assertEquals(List.of(path("a.glsl"), path("b.glsl"), path("c.glsl")),
				new ArrayList<>(outcomes.keySet()));
		assertTrue(outcomes.get(path("a.glsl")) instanceof Saved);
		assertEquals(new Failed("the provider is offline"),
				outcomes.get(path("b.glsl")));
		assertTrue(outcomes.get(path("c.glsl")) instanceof Saved);
		assertEquals("a!", store.text("a.glsl"));
		assertEquals("b", store.text("b.glsl"));
		assertEquals("c!", store.text("c.glsl"));
		assertEquals(Set.of(path("b.glsl")), session.unsaved());
		assertTrue(session.conflicts().isEmpty());
		assertEquals(Set.of(path("b.glsl")),
				new DraftJournal(journalDir).drafts().keySet());

		outcomes = session.save();

		assertEquals(Set.of(path("b.glsl")), outcomes.keySet());
		assertTrue(outcomes.get(path("b.glsl")) instanceof Saved);
		assertEquals("b!", store.text("b.glsl"));
		assertTrue(session.unsaved().isEmpty());
	}

	@Test
	public void refusesToOverwriteChangedFile() throws IOException {
		write("main.glsl", "stored");
		DirectoryProjectFiles store = new DirectoryProjectFiles(root);
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "mine",
				revision(store, "main.glsl"));
		write("main.glsl", "theirs");

		Outcome outcome = session.save().get(path("main.glsl"));

		assertEquals(new Conflict("the file has changed"), outcome);
		assertEquals("theirs", read("main.glsl"));
		assertEquals("mine",
				session.draft(path("main.glsl")).text().toString());
		assertEquals(Set.of(path("main.glsl")),
				session.conflicts().keySet());
	}

	@Test
	public void skipsFilesInConflict() {
		MemoryFiles store = new MemoryFiles().put("main.glsl", "stored");
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "mine",
				store.revision("main.glsl"));
		store.put("main.glsl", "theirs");
		session.refresh();
		int writes = store.writes;

		Outcome outcome = session.save().get(path("main.glsl"));

		assertEquals(new Conflict("the file has changed"), outcome);
		assertEquals(writes, store.writes);
		assertEquals("theirs", store.text("main.glsl"));
	}

	@Test
	public void refreshFindsChangedDeletedAndCreatedFiles() {
		MemoryFiles store = new MemoryFiles()
				.put("changed.glsl", "a")
				.put("deleted.glsl", "b")
				.put("same.glsl", "c")
				.put("unreadable.glsl", "d");
		EditingSession session = open(store);
		for (String name : List.of("changed", "deleted", "same",
				"unreadable")) {
			session.setDraft(path(name + ".glsl"), "mine",
					store.revision(name + ".glsl"));
		}
		session.setDraft(path("created.glsl"), "mine", null);
		session.setDraft(path("new.glsl"), "mine", null);
		store.put("changed.glsl", "theirs");
		store.remove("deleted.glsl");
		store.put("created.glsl", "theirs");
		store.unreadable = path("unreadable.glsl");

		Map<ProjectPath, String> conflicts = session.refresh();

		assertEquals(Map.of(
				path("changed.glsl"), "the file has changed",
				path("deleted.glsl"), "the file was deleted",
				path("created.glsl"), "the file exists"), conflicts);
		assertEquals(6, session.unsaved().size());
	}

	@Test
	public void reloadDropsDraft() {
		MemoryFiles store = new MemoryFiles().put("main.glsl", "stored");
		EditingSession session = conflicted(store);

		assertTrue(session.clearDraft(path("main.glsl")));

		assertNull(session.draft(path("main.glsl")));
		assertTrue(session.conflicts().isEmpty());
		assertEquals("theirs", text(session.files().read(path("main.glsl"))));
		assertTrue(new DraftJournal(journalDir).drafts().isEmpty());
	}

	@Test
	public void keepEditsOverwritesOnNextSave() {
		MemoryFiles store = new MemoryFiles().put("main.glsl", "stored");
		EditingSession session = conflicted(store);

		assertNull(session.keepEdits(path("main.glsl")));

		assertTrue(session.conflicts().isEmpty());
		assertEquals("theirs", store.text("main.glsl"));
		assertEquals(new Draft("mine", store.revision("main.glsl")),
				new DraftJournal(journalDir).drafts().get(path("main.glsl")));
		assertTrue(session.save().get(path("main.glsl")) instanceof Saved);
		assertEquals("mine", store.text("main.glsl"));
	}

	@Test
	public void keepEditsOfDeletedFileCreatesItAgain() {
		MemoryFiles store = new MemoryFiles().put("main.glsl", "stored");
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "mine",
				store.revision("main.glsl"));
		store.remove("main.glsl");
		session.refresh();

		assertNull(session.keepEdits(path("main.glsl")));

		assertNull(session.draft(path("main.glsl")).base());
		assertTrue(session.save().get(path("main.glsl")) instanceof Saved);
		assertEquals("mine", store.text("main.glsl"));
	}

	@Test
	public void keepEditsNeedsReadableFile() {
		MemoryFiles store = new MemoryFiles().put("main.glsl", "stored");
		EditingSession session = conflicted(store);
		store.unreadable = path("main.glsl");

		assertNotNull(session.keepEdits(path("main.glsl")));

		assertEquals(Set.of(path("main.glsl")),
				session.conflicts().keySet());
		assertNotNull(session.keepEdits(path("other.glsl")));
	}

	@Test
	public void saveCopyKeepsBothVersions() throws IOException {
		write("main.glsl", "stored");
		write("taken.glsl", "taken");
		DirectoryProjectFiles store = new DirectoryProjectFiles(root);
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "mine",
				revision(store, "main.glsl"));
		write("main.glsl", "theirs");
		session.refresh();

		assertTrue(session.saveCopy(path("main.glsl"),
				path("taken.glsl")) instanceof Failed);
		assertEquals("mine",
				session.draft(path("main.glsl")).text().toString());
		assertEquals("taken", read("taken.glsl"));

		Outcome outcome = session.saveCopy(path("main.glsl"),
				path("copy/main.glsl"));

		assertEquals(new Saved(revision(store, "copy/main.glsl")), outcome);
		assertEquals("mine", read("copy/main.glsl"));
		assertEquals("theirs", read("main.glsl"));
		assertEquals("mine",
				session.draft(path("main.glsl")).text().toString());
		assertTrue(session.conflicts().containsKey(path("main.glsl")));
	}

	@Test
	public void keepsFirstBaseOfDraft() {
		MemoryFiles store = new MemoryFiles().put("main.glsl", "stored");
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "one", "r0");
		session.setDraft(path("main.glsl"), "two", "other");
		assertEquals(new Draft("two", "r0"), session.draft(path("main.glsl")));
	}

	@Test
	public void keepsEditsMadeWhileSaving() {
		MemoryFiles store = new MemoryFiles().put("main.glsl", "stored");
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "one",
				store.revision("main.glsl"));
		store.onWrite = () -> session.setDraft(path("main.glsl"), "two",
				null);

		Outcome outcome = session.save().get(path("main.glsl"));

		assertEquals("one", store.text("main.glsl"));
		assertEquals(new Draft("two", ((Saved) outcome).revision()),
				session.draft(path("main.glsl")));
	}

	@Test
	public void preparationKeepsDraftItCaptured() {
		MemoryFiles store = new MemoryFiles()
				.put("main.glsl", "#include \"lib.glsl\"\n" +
						"#pragma se input \"a.glsl\"\n" +
						"uniform sampler2D a;\nvoid main() {}\n")
				.put("a.glsl", "#include \"lib.glsl\"\nvoid main() {}\n")
				.put("lib.glsl", "float stored;\n");
		EditingSession session = open(store);
		session.setDraft(path("lib.glsl"), "float one;\n",
				store.revision("lib.glsl"));
		String captured = revision(session.files(), "lib.glsl");
		store.onRead = path -> {
			if (path.equals(path("a.glsl"))) {
				session.setDraft(path("lib.glsl"), "float two;\n", null);
			}
		};

		Preparation preparation = Preparation.prepare(session.files(),
				path("main.glsl"), ProjectLimits.DEFAULT, BuiltinNames.ALL);

		assertNotNull(preparation.diagnostics().toString(),
				preparation.plan());
		assertEquals("float two;\n",
				text(session.files().read(path("lib.glsl"))));
		for (RenderPlan.Program program : preparation.plan().programs()) {
			String source = String.join("\n", program.lines());
			assertTrue(source, source.contains("float one;"));
			assertFalse(source, source.contains("float two;"));
		}
		for (SourceAssembly.Dependency dependency :
				preparation.dependencies()) {
			if (dependency.path().equals(path("lib.glsl"))) {
				assertEquals(captured, dependency.revision());
			}
		}
	}

	@Test
	public void recoversDraftsAfterRestart() throws IOException {
		write("main.glsl", "stored");
		DirectoryProjectFiles store = new DirectoryProjectFiles(root);
		EditingSession before = open(store);
		before.setDraft(path("main.glsl"), "edited",
				revision(store, "main.glsl"));
		before.setDraft(path("new.glsl"), "fresh", null);

		EditingSession after = open(new DirectoryProjectFiles(root));

		assertEquals(before.unsaved(), after.unsaved());
		assertEquals(before.draft(path("main.glsl")),
				after.draft(path("main.glsl")));
		assertEquals(new Draft("fresh", null), after.draft(path("new.glsl")));
		assertEquals("edited", text(after.files().read(path("main.glsl"))));
		assertTrue(after.conflicts().isEmpty());
		assertEquals("stored", read("main.glsl"));
	}

	@Test
	public void recoveryReportsBaseChangedWhileDead() throws IOException {
		write("main.glsl", "stored");
		DirectoryProjectFiles store = new DirectoryProjectFiles(root);
		open(store).setDraft(path("main.glsl"), "mine",
				revision(store, "main.glsl"));
		write("main.glsl", "theirs");

		EditingSession after = open(store);

		assertEquals(Map.of(path("main.glsl"), "the file has changed"),
				after.conflicts());
		assertTrue(after.save().get(path("main.glsl")) instanceof Conflict);
		assertEquals("theirs", read("main.glsl"));
		assertEquals("mine", after.draft(path("main.glsl")).text().toString());
	}

	@Test
	public void recoveryIgnoresHalfWrittenDraft() throws IOException {
		write("main.glsl", "stored");
		DirectoryProjectFiles store = new DirectoryProjectFiles(root);
		open(store).setDraft(path("main.glsl"), "mine",
				revision(store, "main.glsl"));
		Files.write(new File(journalDir, ".journal-9.tmp").toPath(),
				new byte[]{0x53, 0x45});

		EditingSession after = open(store);

		assertEquals(Set.of(path("main.glsl")), after.unsaved());
		assertEquals("mine", after.draft(path("main.glsl")).text().toString());
	}

	private EditingSession conflicted(MemoryFiles store) {
		EditingSession session = open(store);
		session.setDraft(path("main.glsl"), "mine",
				store.revision("main.glsl"));
		store.put("main.glsl", "theirs");
		assertEquals(Set.of(path("main.glsl")),
				session.refresh().keySet());
		return session;
	}

	private EditingSession open(ProjectFiles store) {
		return EditingSession.restore(store, new DraftJournal(journalDir));
	}

	private void write(String path, String text) throws IOException {
		Files.write(new File(root, path).toPath(),
				text.getBytes(StandardCharsets.UTF_8));
	}

	private String read(String path) throws IOException {
		return new String(Files.readAllBytes(new File(root, path).toPath()),
				StandardCharsets.UTF_8);
	}

	private static String revision(ProjectFiles store, String path) {
		return ((Content) store.read(path(path))).revision();
	}

	private static String text(ProjectFiles.Read read) {
		return new String(((Content) read).bytes(), StandardCharsets.UTF_8);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	// A store that can fail one write or the reads of one file, and
	// gives every version of a file a new revision.
	private static final class MemoryFiles implements ProjectFiles {
		private final Map<ProjectPath, Content> files = new HashMap<>();
		private int revisions;
		int writes;
		int failWrite;
		ProjectPath unreadable;
		Runnable onWrite;
		Consumer<ProjectPath> onRead;

		MemoryFiles put(String path, String text) {
			files.put(path(path), new Content(
					text.getBytes(StandardCharsets.UTF_8),
					"r" + (++revisions)));
			return this;
		}

		void remove(String path) {
			files.remove(path(path));
		}

		String revision(String path) {
			return files.get(path(path)).revision();
		}

		String text(String path) {
			return EditingSessionTest.text(files.get(path(path)));
		}

		@Override
		public Read read(ProjectPath path) {
			if (onRead != null) {
				onRead.accept(path);
			}
			return path.equals(unreadable)
					? new ProjectFiles.Failed("permission was lost")
					: files.get(path);
		}

		@Override
		public Change write(ProjectPath path, byte[] bytes,
				String expectedRevision) {
			if (onWrite != null) {
				onWrite.run();
			}
			if (++writes == failWrite) {
				return new Refused(Refusal.FAILED, "the provider is offline");
			}
			Content content = files.get(path);
			if (!Objects.equals(content != null ? content.revision() : null,
					expectedRevision)) {
				return new Refused(Refusal.CONFLICT, content == null
						? "the file was deleted"
						: expectedRevision == null
								? "the file exists"
								: "the file has changed");
			}
			Content written = new Content(bytes.clone(),
					"r" + (++revisions));
			files.put(path, written);
			return new Done(written.revision());
		}
	}
}
