package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.markusfisch.android.shadereditor.editor.Document.Change;
import de.markusfisch.android.shadereditor.editor.Document.Selection;

public class DocumentHistoryTest {
	private final long[] now = {0};

	@Test
	public void undoToTheStartRestoresTheFirstText() {
		Random random = new Random(5);
		for (int round = 0; round < 40; ++round) {
			StringBuilder sb = new StringBuilder();
			for (int i = random.nextInt(3000); i-- > 0; ) {
				sb.append(random.nextInt(10) == 0 ? '\n' : 'a');
			}
			String first = sb.toString();
			Document document = document(first);
			for (int i = 0; i < 400; ++i) {
				now[0] += random.nextInt(5) == 0 ? 1000 : random.nextInt(300);
				step(random, document);
			}
			String last = document.text().toString();
			int undone = 0;
			while (document.canUndo()) {
				document.undo();
				++undone;
			}
			assertEquals(first, document.text().toString());
			assertFalse(document.isModified());
			for (int i = 0; i < undone; ++i) {
				document.redo();
			}
			assertEquals(last, document.text().toString());
		}
	}

	@Test
	public void restoredHistoryUndoesAndRedoesAsTheOriginal() {
		Random random = new Random(7);
		Document original = document("start\nof the text\n");
		for (int i = 0; i < 300; ++i) {
			now[0] += random.nextInt(5) == 0 ? 1000 : random.nextInt(300);
			step(random, original);
		}
		original.markSaved(original.state());
		original.undo();
		Document restored = document(original.text().toString());

		assertTrue(restored.restore(original.history()));
		assertFalse(restored.restore(original.history()));

		assertEquals(original.selection(), restored.selection());
		assertEquals(original.historyChars(), restored.historyChars());
		while (original.canUndo()) {
			assertTrue(restored.canUndo());
			original.undo();
			restored.undo();
			assertSame(original, restored);
		}
		assertFalse(restored.canUndo());
		while (original.canRedo()) {
			original.redo();
			restored.redo();
			assertSame(original, restored);
		}
		assertFalse(restored.canRedo());
		original.replace(0, 0, Text.of("x"));
		restored.replace(0, 0, Text.of("x"));
		original.undo();
		restored.undo();
		assertSame(original, restored);
	}

	@Test
	public void historyIsNotTakenWhereItsSelectionCannotBe() {
		Document original = document("a longer text");
		original.select(10, 10);
		original.replace(10, 10, Text.of("!"));

		assertFalse(document("short").restore(original.history()));
	}

	// Asserts that b holds what a holds.
	private static void assertSame(Document a, Document b) {
		assertEquals(a.text().toString(), b.text().toString());
		assertEquals(a.selection(), b.selection());
		assertEquals(a.isModified(), b.isModified());
		assertEquals(a.state(), b.state());
	}

	@Test
	public void redoReturnsWhatUndoTook() {
		Document document = document("ab");
		document.select(2, 2);
		document.replace(2, 2, Text.of("cd"));
		document.select(0, 1);
		document.replace(0, 1, Text.of("X"));
		document.undo();
		assertEquals("abcd", document.text().toString());
		assertEquals(new Selection(0, 1), document.selection());
		document.undo();
		assertEquals("ab", document.text().toString());
		assertEquals(new Selection(2, 2), document.selection());
		assertFalse(document.canUndo());
		document.redo();
		assertEquals("abcd", document.text().toString());
		assertEquals(new Selection(4, 4), document.selection());
		document.redo();
		assertEquals("Xbcd", document.text().toString());
		assertEquals(new Selection(1, 1), document.selection());
		assertFalse(document.canRedo());
	}

	@Test
	public void runOfTypingIsOneStep() {
		Document document = document("x");
		document.select(1, 1);
		type(document, "abc");
		backspace(document);
		type(document, "d");
		assertEquals("xabd", document.text().toString());
		document.undo();
		assertEquals("x", document.text().toString());
		assertEquals(new Selection(1, 1), document.selection());
		assertFalse(document.canUndo());
		document.redo();
		assertEquals("xabd", document.text().toString());
		assertEquals(new Selection(4, 4), document.selection());
	}

	@Test
	public void deletingBeforeTheRunJoinsIt() {
		Document document = document("xy");
		document.select(2, 2);
		type(document, "a");
		backspace(document);
		backspace(document);
		document.type(1, 1, Text.of("b"));
		assertEquals("xb", document.text().toString());
		document.undo();
		assertEquals("xy", document.text().toString());
		assertFalse(document.canUndo());
	}

	@Test
	public void pauseEndsTheRun() {
		Document document = document("");
		type(document, "ab");
		now[0] += 999;
		type(document, "c");
		now[0] += 1000;
		type(document, "d");
		document.undo();
		assertEquals("abc", document.text().toString());
		document.undo();
		assertEquals("", document.text().toString());
	}

	@Test
	public void movingTheCaretEndsTheRun() {
		Document document = document("");
		type(document, "ab");
		document.select(0, 0);
		document.select(2, 2);
		type(document, "c");
		document.undo();
		assertEquals("ab", document.text().toString());
	}

	@Test
	public void typingElsewhereStartsAStep() {
		Document document = document("0123456789");
		document.type(2, 2, Text.of("a"));
		document.type(8, 8, Text.of("b"));
		document.undo();
		assertEquals("01a23456789", document.text().toString());
	}

	@Test
	public void otherEditsEndTheRun() {
		Document document = document("");
		type(document, "ab");
		document.replace(2, 2, Text.of("PASTE"));
		type(document, "c");
		assertEquals("abPASTEc", document.text().toString());
		document.undo();
		assertEquals("abPASTE", document.text().toString());
		document.undo();
		assertEquals("ab", document.text().toString());
		document.undo();
		assertEquals("", document.text().toString());
	}

	@Test
	public void undoEndsTheRun() {
		Document document = document("");
		type(document, "ab");
		document.undo();
		type(document, "c");
		type(document, "d");
		document.undo();
		assertEquals("", document.text().toString());
		document.redo();
		assertEquals("cd", document.text().toString());
	}

	@Test
	public void batchIsOneStep() {
		Document document = document("one two");
		document.beginBatch();
		document.replace(0, 3, Text.of("1"));
		document.replace(2, 5, Text.of("2"));
		document.select(0, 3);
		document.endBatch();
		assertEquals("1 2", document.text().toString());
		document.undo();
		assertEquals("one two", document.text().toString());
		assertFalse(document.canUndo());
		document.redo();
		assertEquals(new Selection(0, 3), document.selection());
	}

	@Test
	public void typingInBatchesContinuesTheRun() {
		Document document = document("");
		for (char c : "abc".toCharArray()) {
			document.beginBatch();
			type(document, String.valueOf(c));
			document.endBatch();
		}
		document.undo();
		assertEquals("", document.text().toString());
	}

	@Test
	public void correctionInABatchIsAStepOfItsOwn() {
		Document document = document("");
		type(document, "teh");
		document.beginBatch();
		document.replace(0, 3, Text.EMPTY);
		document.type(0, 0, Text.of("the"));
		document.endBatch();
		type(document, "y");
		document.undo();
		assertEquals("the", document.text().toString());
		document.undo();
		assertEquals("teh", document.text().toString());
	}

	@Test
	public void batchWithAnEditLeavesTheTypingBeforeIt() {
		Document document = document("");
		type(document, "a");
		document.beginBatch();
		type(document, "b");
		document.replace(0, 1, Text.of("A"));
		document.endBatch();
		document.undo();
		assertEquals("a", document.text().toString());
	}

	@Test
	public void undoInsideABatch() {
		Document document = document("");
		Events events = new Events(document);
		document.replace(0, 0, Text.of("ab"));
		events.updates = 0;
		document.beginBatch();
		document.type(2, 2, Text.of("c"));
		document.undo();
		document.type(0, 0, Text.of("x"));
		document.endBatch();
		assertEquals("xab", document.text().toString());
		assertEquals(1, events.updates);
		document.undo();
		assertEquals("ab", document.text().toString());
		document.undo();
		assertEquals("", document.text().toString());
	}

	@Test
	public void listenersHearUndoAsChanges() {
		Document document = document("abc");
		document.beginBatch();
		document.replace(0, 1, Text.of("X"));
		document.replace(3, 3, Text.of("Y"));
		document.endBatch();
		Events events = new Events(document);
		int revision = document.revision();
		document.undo();
		assertEquals(List.of("3 -Y +", "0 -X +a"), events.changes);
		assertEquals(1, events.updates);
		assertEquals(revision + 2, document.revision());
		document.redo();
		assertEquals(List.of("3 -Y +", "0 -X +a", "0 -a +X", "3 - +Y"),
				events.changes);
	}

	@Test
	public void modifiedFollowsTheSavedPoint() {
		Document document = document("");
		assertFalse(document.isModified());
		type(document, "ab");
		assertTrue(document.isModified());
		document.markSaved(document.state());
		assertFalse(document.isModified());
		type(document, "c");
		assertTrue(document.isModified());
		document.undo();
		assertEquals("ab", document.text().toString());
		assertFalse(document.isModified());
		document.undo();
		assertTrue(document.isModified());
		document.redo();
		assertFalse(document.isModified());
		document.redo();
		assertTrue(document.isModified());
	}

	@Test
	public void savedPointLostWithTheRedoSteps() {
		Document document = document("");
		type(document, "a");
		document.markSaved(document.state());
		document.undo();
		type(document, "b");
		assertTrue(document.isModified());
		document.undo();
		assertTrue(document.isModified());
	}

	@Test
	public void savingAnOlderTextLeavesTheDocumentModified() {
		Document document = document("");
		type(document, "a");
		int saving = document.state();
		type(document, "b");
		document.markSaved(saving);
		assertTrue(document.isModified());
		document.undo();
		assertTrue(document.isModified());
		document.redo();
		document.markSaved(document.state());
		assertFalse(document.isModified());
	}

	@Test
	public void undoToATextSavedWhileEditingIsUnmodified() {
		Document document = document("a");
		document.select(1, 1);
		type(document, "b");
		int saving = document.state();
		document.replace(2, 2, Text.of("c"));
		document.markSaved(saving);
		document.undo();
		assertEquals("ab", document.text().toString());
		assertFalse(document.isModified());
	}

	@Test
	public void restoredDraftIsModified() {
		Document document = document("draft");
		document.markSaved(-1);
		assertTrue(document.isModified());
	}

	@Test
	public void historyKeepsAThousandSteps() {
		Document document = document("");
		for (int i = 0; i < 1500; ++i) {
			document.replace(i, i, Text.of("x"));
		}
		int steps = 0;
		while (document.canUndo()) {
			document.undo();
			++steps;
		}
		assertEquals(1000, steps);
		assertEquals(500, document.text().length());
		assertTrue(document.isModified());
	}

	@Test
	public void historyKeepsFourMillionCharacters() {
		Document document = document("");
		Text million = Text.of("y".repeat(1_000_000));
		for (int i = 0; i < 6; ++i) {
			document.replace(0, 0, million);
		}
		int steps = 0;
		while (document.canUndo()) {
			document.undo();
			++steps;
		}
		assertEquals(4, steps);
		assertEquals(2_000_000, document.text().length());
	}

	@Test
	public void newestStepIsKeptWhateverItsSize() {
		Document document = document("abc");
		document.replace(0, 3, Text.of("z".repeat(5_000_000)));
		assertTrue(document.canUndo());
		document.undo();
		assertEquals("abc", document.text().toString());
		assertFalse(document.isModified());
	}

	@Test
	public void savedPointDroppedFromTheStartOfHistory() {
		Document document = document("");
		document.markSaved(document.state());
		for (int i = 0; i < 1001; ++i) {
			document.replace(i, i, Text.of("x"));
		}
		while (document.canUndo()) {
			document.undo();
		}
		assertEquals(1, document.text().length());
		assertTrue(document.isModified());
	}

	@Test
	public void savedStepBecomesTheStartOfHistory() {
		Document document = document("");
		document.replace(0, 0, Text.of("x"));
		document.markSaved(document.state());
		for (int i = 1; i <= 1000; ++i) {
			document.replace(i, i, Text.of("x"));
		}
		while (document.canUndo()) {
			document.undo();
		}
		assertEquals(1, document.text().length());
		assertFalse(document.isModified());
	}

	private Document document(String text) {
		return new Document(Text.of(text), () -> now[0]);
	}

	// One random edit, selection, batch, or undo and redo.
	private static void step(Random random, Document document) {
		int length = document.text().length();
		Selection selection = document.selection();
		switch (random.nextInt(12)) {
			case 0:
				document.select(random.nextInt(length + 1),
						random.nextInt(length + 1));
				break;
			case 1:
				document.beginBatch();
				for (int i = random.nextInt(3); i-- > 0; ) {
					step(random, document);
				}
				document.endBatch();
				break;
			case 2:
				String text = document.text().toString();
				int steps = random.nextInt(5);
				int undone = 0;
				for (; undone < steps && document.canUndo(); ++undone) {
					document.undo();
				}
				for (; undone > 0; --undone) {
					document.redo();
				}
				assertEquals(text, document.text().toString());
				break;
			case 3:
				document.undo();
				break;
			case 4:
				int from = random.nextInt(length + 1);
				int to = Math.min(length, from + random.nextInt(50));
				document.replace(from, to,
						Text.of("pasted\n".repeat(random.nextInt(4))));
				break;
			case 5:
				if (selection.start() > 0) {
					document.type(selection.start() - 1, selection.end(),
							Text.EMPTY);
				}
				break;
			default:
				document.type(selection.start(), selection.end(),
						Text.of(random.nextInt(8) == 0 ? "\n" : "b"));
				break;
		}
	}

	private static void type(Document document, String typed) {
		for (char c : typed.toCharArray()) {
			Selection selection = document.selection();
			document.type(selection.start(), selection.end(),
					Text.of(String.valueOf(c)));
		}
	}

	private static void backspace(Document document) {
		int caret = document.selection().head();
		document.type(caret - 1, caret, Text.EMPTY);
	}

	private static final class Events implements Document.Listener {
		private final List<String> changes = new ArrayList<>();
		private int updates;

		private Events(Document document) {
			document.addListener(this);
		}

		@Override
		public void onChange(Change change, Text before) {
			changes.add(change.start() + " -" + change.removed() + " +" +
					change.inserted());
		}

		@Override
		public void onUpdate() {
			++updates;
		}
	}
}
