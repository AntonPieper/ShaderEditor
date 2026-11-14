package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EditorDraftTest {
	@Rule
	public final TemporaryFolder folder = new TemporaryFolder();

	@Test
	public void readsOnlyTheLatestTextOfItsKey() {
		EditorDraft draft = new EditorDraft(folder.getRoot());
		draft.write("a", "void main() {\n}\n");
		draft.write("b", "// ä\n");
		assertNull(draft.read("a"));
		assertEquals("// ä\n", draft.read("b"));
		draft.delete();
		assertNull(draft.read("b"));
	}
}
