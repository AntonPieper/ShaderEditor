package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.EditingSession.Conflict;
import de.markusfisch.android.shadereditor.project.EditingSession.Failed;
import de.markusfisch.android.shadereditor.project.EditingSession.Saved;
import de.markusfisch.android.shadereditor.project.FileTexts.Opened;
import de.markusfisch.android.shadereditor.project.FileTexts.Opening;
import de.markusfisch.android.shadereditor.project.FileTexts.Save;
import de.markusfisch.android.shadereditor.project.FileTexts.Unopened;

public class FileTextsTest {
	private static final ProjectPath MAIN = OpenProject.MAIN;

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private final Queue worker = new Queue();
	private final Queue main = new Queue();
	private final List<Save> saves = new ArrayList<>();
	private File drafts;
	private Document document;
	// The revision the document's text came from.
	private String base;
	private MemoryProjectFiles store;
	private FileTexts texts;

	@Before
	public void setUp() {
		drafts = new File(temp.getRoot(), "drafts");
		store = new MemoryProjectFiles();
		store.writable = true;
	}

	@Test
	public void opensEveryLineEndAndSavesLineFeeds() {
		store.put("main.glsl", "﻿a\r\nb\rc\nd");
		document = new Document(opened(MAIN, ProjectFiles.UNKNOWN));
		assertEquals("a\nb\nc\nd", document.text().toString());
		assertEquals(4, document.text().lineCount());

		document.replace(0, 1, Text.of("A"));
		save(document);
		assertTrue(saves.get(0).outcome() instanceof Saved);
		assertEquals("A\nb\nc\nd", store.text("main.glsl"));
	}

	@Test
	public void refusesFileOverLimitWithoutReadingIt() {
		store.put("main.glsl", new byte[(int) FileTexts.MAX_BYTES + 1]);
		Opening opening = open(MAIN, FileTexts.MAX_BYTES + 1);
		assertEquals("main.glsl is larger than 16 MiB",
				((Unopened) opening).reason());
		assertEquals(0, store.reads);

		opening = open(MAIN, ProjectFiles.UNKNOWN);
		assertTrue(opening instanceof Unopened);
		assertEquals(1, store.reads);
	}

	@Test
	public void saveWritesTextCapturedBeforeLaterEdit() {
		store.put("main.glsl", "one");
		document = new Document(opened(MAIN, 3));
		document.replace(3, 3, Text.of(" two"));
		texts.save(MAIN, document.text(), document.state(), base,
				this::saved);
		document.replace(7, 7, Text.of(" three"));
		worker.run();
		main.run();

		assertEquals("one two", store.text("main.glsl"));
		assertTrue(document.isModified());
	}

	@Test
	public void undoBackToSavedTextIsUnmodified() {
		store.put("main.glsl", "one");
		document = new Document(opened(MAIN, 3));
		document.replace(3, 3, Text.of(" two"));
		texts.save(MAIN, document.text(), document.state(), base,
				this::saved);
		document.replace(7, 7, Text.of(" three"));
		worker.run();
		main.run();

		document.undo();
		assertFalse(document.isModified());
	}

	@Test
	public void failedSaveKeepsDocumentModifiedAndSaysWhy() {
		store.put("main.glsl", "one");
		document = new Document(opened(MAIN, 3));
		document.replace(3, 3, Text.of(" two"));
		store.failure = "the disk is full";
		save(document);

		assertEquals(new Failed("the disk is full"), saves.get(0).outcome());
		assertTrue(document.isModified());
		assertEquals("one", store.text("main.glsl"));
	}

	@Test
	public void changeElsewhereIsAConflictThatKeepsTheText() {
		store.put("main.glsl", "one");
		document = new Document(opened(MAIN, 3));
		document.replace(3, 3, Text.of(" two"));
		store.put("main.glsl", "elsewhere");
		save(document);

		assertTrue(saves.get(0).outcome() instanceof Conflict);
		assertTrue(document.isModified());
		assertEquals("elsewhere", store.text("main.glsl"));
		EditingSession session = EditingSession.restore(store,
				new DraftJournal(drafts));
		assertEquals("one two", session.draft(MAIN).text().toString());
		assertTrue(session.conflicts().containsKey(MAIN));
	}

	@Test
	public void draftIsReopenedAsUnsavedText() {
		store.put("main.glsl", "one");
		document = new Document(opened(MAIN, 3));
		document.replace(3, 3, Text.of("\r\ntwo"));
		texts.draft(MAIN, document.text(), document.isModified(), base);
		worker.run();

		texts = new FileTexts(EditingSession.restore(store,
				new DraftJournal(drafts)), worker, main);
		Opened opened = (Opened) open(MAIN, 3);
		assertTrue(opened.unsaved());
		assertEquals("one\ntwo", opened.text().toString());
		assertEquals(2, opened.text().lineCount());
	}

	@Test
	public void savingLargeTextWritesBoundedChunks() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 500_000; ++i) {
			sb.append("float v").append(i).append(" = 1.0;\n");
		}
		Text text = Text.of(sb);
		assertEquals(500_001, text.lineCount());
		texts = new FileTexts(EditingSession.restore(store,
				new DraftJournal(drafts)), worker, main);
		texts.save(MAIN, text, 0, null, this::saved);
		worker.run();
		main.run();

		assertTrue(saves.get(0).outcome() instanceof Saved);
		assertTrue("largest chunk " + store.largestChunk,
				store.largestChunk <= 64 * 1024);
		assertEquals(sb.toString(), store.text("main.glsl"));
	}

	@Test
	public void rereadGivesAChangedFileThatSavesOverItsRevision() {
		store.put("main.glsl", "one");
		document = new Document(opened(MAIN, 3));
		assertNull(reread(MAIN));
		store.put("main.glsl", "two");

		FileTexts.Changed changed = reread(MAIN);

		assertEquals("two", changed.text().toString());
		base = changed.revision();
		assertNull(reread(MAIN));
		document.replace(0, 3, Text.of("three"));
		save(document);
		assertTrue(saves.get(0).outcome() instanceof Saved);
		assertEquals("three", store.text("main.glsl"));
	}

	@Test
	public void rereadWithoutTakingItsRevisionKeepsTheConflict() {
		store.put("main.glsl", "one");
		document = new Document(opened(MAIN, 3));
		store.put("main.glsl", "two");
		assertEquals("two", reread(MAIN).text().toString());

		document.replace(0, 3, Text.of("mine"));
		save(document);

		assertTrue(saves.get(0).outcome() instanceof Conflict);
		assertEquals("two", store.text("main.glsl"));
	}

	private FileTexts.Changed reread(ProjectPath path) {
		FileTexts.Changed[] changed = {null};
		texts.reread(path, base, result -> changed[0] = result);
		worker.run();
		main.run();
		return changed[0];
	}

	private Text opened(ProjectPath path, long size) {
		Opened opened = (Opened) open(path, size);
		base = opened.revision();
		return opened.text();
	}

	private Opening open(ProjectPath path, long size) {
		if (texts == null) {
			texts = new FileTexts(EditingSession.restore(store,
					new DraftJournal(drafts)), worker, main);
		}
		Opening[] opening = new Opening[1];
		texts.open(path, size, result -> opening[0] = result);
		worker.run();
		assertNull(opening[0]);
		main.run();
		return opening[0];
	}

	private void save(Document document) {
		texts.save(MAIN, document.text(), document.state(), base,
				this::saved);
		worker.run();
		main.run();
	}

	// What the editor does with a finished save, on the main thread.
	private void saved(Save save) {
		saves.add(save);
		if (document != null && save.outcome() instanceof Saved) {
			document.markSaved(save.state());
		}
	}

	private static final class Queue implements Executor {
		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

		@Override
		public void execute(Runnable task) {
			tasks.add(task);
		}

		void run() {
			for (Runnable task; (task = tasks.poll()) != null; ) {
				task.run();
			}
		}
	}
}
