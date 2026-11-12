package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.markusfisch.android.shadereditor.editor.Document.Selection;

public class EditCommandsTest {
	@Test
	public void moveCollapsesASelectionToItsEnds() {
		Document document = documentOf("a^bc|d");
		new EditCommands(document).moveBackward(false);
		assertEquals("a|bcd", stateOf(document));
		document = documentOf("a|bc^d");
		new EditCommands(document).moveForward(false);
		assertEquals("abc|d", stateOf(document));
	}

	@Test
	public void extendKeepsTheAnchor() {
		Document document = documentOf("a^bc|d");
		EditCommands commands = new EditCommands(document);
		commands.moveForward(true);
		assertEquals("a^bcd|", stateOf(document));
		commands.moveWordBackward(true);
		assertEquals("|a^bcd", stateOf(document));
	}

	@Test
	public void movesByCharAndWord() {
		Document document = documentOf("|ab cd");
		EditCommands commands = new EditCommands(document);
		commands.moveForward(false);
		assertEquals("a|b cd", stateOf(document));
		commands.moveWordForward(false);
		assertEquals("ab| cd", stateOf(document));
		commands.moveWordForward(false);
		assertEquals("ab cd|", stateOf(document));
		commands.moveBackward(false);
		assertEquals("ab c|d", stateOf(document));
	}

	@Test
	public void verticalMovesKeepTheWantedColumn() {
		Document document = documentOf("abcde|f\nab\nabcdefgh");
		EditCommands commands = new EditCommands(document);
		commands.moveLines(1, false);
		assertEquals("abcdef\nab|\nabcdefgh", stateOf(document));
		commands.moveLines(1, false);
		assertEquals("abcdef\nab\nabcde|fgh", stateOf(document));
	}

	@Test
	public void anotherMoveForgetsTheWantedColumn() {
		Document document = documentOf("abcde|f\nab\nabcdefgh");
		EditCommands commands = new EditCommands(document);
		commands.moveLines(1, false);
		commands.moveBackward(false);
		commands.moveLines(1, false);
		assertEquals("abcdef\nab\na|bcdefgh", stateOf(document));
	}

	@Test
	public void anEditForgetsTheWantedColumn() {
		Document document = documentOf("abcde|f\nab\nabcdefgh");
		EditCommands commands = new EditCommands(document);
		commands.moveLines(1, false);
		document.replace(10, 10, Text.of("x"));
		commands.moveLines(1, false);
		assertEquals("abcdef\nab\nxa|bcdefgh", stateOf(document));
	}

	@Test
	public void verticalMovesMeasureTabStops() {
		Document document = documentOf("\t|x\nabcdef");
		EditCommands commands = new EditCommands(document);
		commands.moveLines(1, false);
		assertEquals("\tx\nabcd|ef", stateOf(document));
		commands.setTabWidth(2);
		commands.moveToStart(false);
		commands.moveForward(false);
		commands.moveLines(1, true);
		assertEquals("\t^x\nab|cdef", stateOf(document));
	}

	@Test
	public void verticalMovesStopAtTheEnds() {
		Document document = documentOf("ab|c\nabc");
		EditCommands commands = new EditCommands(document);
		commands.moveLines(-1, false);
		assertEquals("|abc\nabc", stateOf(document));
		commands.moveLines(5, true);
		assertEquals("^abc\nabc|", stateOf(document));
		commands.moveLines(-1, false);
		assertEquals("ab|c\nabc", stateOf(document));
	}

	@Test
	public void movesToLineAndTextEnds() {
		Document document = documentOf("ab\n\tc|d\nef");
		EditCommands commands = new EditCommands(document);
		commands.moveToLineStart(false);
		assertEquals("ab\n|\tcd\nef", stateOf(document));
		commands.moveToLineEnd(true);
		assertEquals("ab\n^\tcd|\nef", stateOf(document));
		commands.moveToEnd(false);
		assertEquals("ab\n\tcd\nef|", stateOf(document));
		commands.moveToStart(true);
		assertEquals("|ab\n\tcd\nef^", stateOf(document));
	}

	@Test
	public void selectsAllWordAndLine() {
		Document document = documentOf("float x;\nvec2 uv;");
		EditCommands commands = new EditCommands(document);
		commands.selectAll();
		assertEquals(new Selection(0, 17), document.selection());
		commands.selectWord(2);
		assertEquals(new Selection(0, 5), document.selection());
		commands.selectLine(3);
		assertEquals(new Selection(0, 9), document.selection());
		commands.selectLine(12);
		assertEquals(new Selection(9, 17), document.selection());
	}

	// Returns a document of state: '|' marks the head of the selection
	// and '^' its anchor, if that is elsewhere.
	static Document documentOf(String state) {
		int head = state.indexOf('|');
		int anchor = state.indexOf('^');
		String text = state.replace("|", "").replace("^", "");
		if (anchor < 0) {
			anchor = head;
		} else if (anchor > head) {
			--anchor;
		} else {
			--head;
		}
		Document document = new Document(Text.of(text));
		document.select(Math.max(anchor, 0), Math.max(head, 0));
		return document;
	}

	static String stateOf(Document document) {
		Selection selection = document.selection();
		StringBuilder sb = new StringBuilder(document.text().toString());
		int head = selection.head();
		int anchor = selection.anchor();
		if (anchor > head) {
			sb.insert(anchor, '^').insert(head, '|');
		} else {
			sb.insert(head, '|');
			if (anchor < head) {
				sb.insert(anchor, '^');
			}
		}
		return sb.toString();
	}
}
