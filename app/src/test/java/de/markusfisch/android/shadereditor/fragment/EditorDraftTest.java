package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;

public class EditorDraftTest {
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void readsBackWhatWasWrittenUnderTheSameKey() throws IOException {
		File dir = folder.newFolder();
		String text = "void main() {\n\tgl_FragColor = vec4(1.0); // ä 😀\n}\n";
		new EditorDraft(dir).write("one", text);
		// On the disk when write() returns: a process started later
		// reads it.
		assertEquals(text, new EditorDraft(dir).read("one"));
	}

	@Test
	public void keepsALargeText() throws IOException {
		StringBuilder sb = new StringBuilder();
		while (sb.length() < 2_000_000) {
			sb.append("\tfloat x = sin(1.0); // line\n");
		}
		EditorDraft draft = new EditorDraft(folder.newFolder());
		draft.write("big", sb.toString());
		assertEquals(sb.toString(), draft.read("big"));
	}

	@Test
	public void keepsAnEmptyText() throws IOException {
		EditorDraft draft = new EditorDraft(folder.newFolder());
		draft.write("empty", "");
		assertEquals("", draft.read("empty"));
	}

	@Test
	public void ignoresADraftOfAnotherKey() throws IOException {
		EditorDraft draft = new EditorDraft(folder.newFolder());
		draft.write("old", "old text");
		assertNull(draft.read("new"));
		// A key that is a prefix of the written one is another key.
		assertNull(draft.read("ol"));
	}

	@Test
	public void aTextWithLineBreaksDoesNotChangeTheKey() throws IOException {
		EditorDraft draft = new EditorDraft(folder.newFolder());
		draft.write("key", "\nkey\n");
		assertEquals("\nkey\n", draft.read("key"));
		assertNull(draft.read("\nkey"));
	}

	@Test
	public void aNewWriteReplacesTheOldOne() throws IOException {
		EditorDraft draft = new EditorDraft(folder.newFolder());
		draft.write("old", "old text");
		draft.write("new", "new text");
		assertNull(draft.read("old"));
		assertEquals("new text", draft.read("new"));
	}

	@Test
	public void nothingToReadWithoutADraft() throws IOException {
		EditorDraft draft = new EditorDraft(folder.newFolder());
		assertNull(draft.read("any"));
	}

	@Test
	public void deleteRemovesTheDraft() throws IOException {
		File dir = folder.newFolder();
		EditorDraft draft = new EditorDraft(dir);
		draft.write("key", "text");
		draft.delete();
		assertNull(draft.read("key"));
		String[] left = dir.list();
		assertTrue(left == null || left.length == 0);
	}
}
