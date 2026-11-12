package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class EditorDraftTest {
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	private static EditorDraft.Dir dirOf(File dir) {
		return () -> dir;
	}

	@Test
	public void readsBackWhatWasSavedUnderTheSameKey() throws IOException {
		EditorDraft draft = new EditorDraft(dirOf(folder.newFolder()), Runnable::run);
		String text = "void main() {\n\tgl_FragColor = vec4(1.0); // ä 😀\n}\n";
		draft.save("one", text);
		assertEquals(text, draft.load("one"));
	}

	@Test
	public void keepsALargeText() throws IOException {
		StringBuilder sb = new StringBuilder();
		while (sb.length() < 2_000_000) {
			sb.append("\tfloat x = sin(1.0); // line\n");
		}
		EditorDraft draft = new EditorDraft(dirOf(folder.newFolder()), Runnable::run);
		draft.save("big", sb.toString());
		assertEquals(sb.toString(), draft.load("big"));
	}

	@Test
	public void keepsAnEmptyText() throws IOException {
		EditorDraft draft = new EditorDraft(dirOf(folder.newFolder()), Runnable::run);
		draft.save("empty", "");
		assertEquals("", draft.load("empty"));
	}

	@Test
	public void ignoresADraftOfAnotherKey() throws IOException {
		EditorDraft draft = new EditorDraft(dirOf(folder.newFolder()), Runnable::run);
		draft.save("old", "old text");
		assertNull(draft.load("new"));
		// A key that is a prefix of the saved one is another key.
		assertNull(draft.load("ol"));
	}

	@Test
	public void aNewSaveReplacesTheOldOne() throws IOException {
		EditorDraft draft = new EditorDraft(dirOf(folder.newFolder()), Runnable::run);
		draft.save("old", "old text");
		draft.save("new", "new text");
		assertNull(draft.load("old"));
		assertEquals("new text", draft.load("new"));
	}

	@Test
	public void theOldDraftStaysUntilTheWriteRuns() throws IOException {
		List<Runnable> queued = new ArrayList<>();
		EditorDraft draft = new EditorDraft(dirOf(folder.newFolder()), queued::add);
		draft.save("old", "old text");
		queued.remove(0).run();
		draft.save("new", "new text");
		assertEquals("old text", draft.load("old"));
		assertNull(draft.load("new"));
		queued.remove(0).run();
		assertEquals("new text", draft.load("new"));
	}

	@Test
	public void nothingToReadWithoutADraft() throws IOException {
		EditorDraft draft = new EditorDraft(dirOf(folder.newFolder()), Runnable::run);
		assertNull(draft.load("any"));
	}

	@Test
	public void deleteRemovesTheDraft() throws IOException {
		File dir = folder.newFolder();
		EditorDraft draft = new EditorDraft(dirOf(dir), Runnable::run);
		draft.save("key", "text");
		draft.delete();
		assertNull(draft.load("key"));
		String[] left = dir.list();
		assertTrue(left == null || left.length == 0);
	}

	@Test
	public void aTextWithLineBreaksDoesNotChangeTheKey() {
		byte[] content = EditorDraft.encode("key", "\nkey\n");
		assertEquals("\nkey\n", EditorDraft.decode("key", content, content.length));
		assertNull(EditorDraft.decode("\nkey", content, content.length));
	}

	@Test
	public void aTruncatedFileWithoutKeyLineIsIgnored() {
		byte[] content = EditorDraft.encode("key", "text");
		assertNull(EditorDraft.decode("key", content, 2));
	}

	@Test
	public void keysDiffer() {
		assertNotEquals(EditorDraft.newKey(), EditorDraft.newKey());
		assertFalse(EditorDraft.newKey().contains("\n"));
	}
}
