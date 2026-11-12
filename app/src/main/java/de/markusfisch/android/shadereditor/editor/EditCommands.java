package de.markusfisch.android.shadereditor.editor;

import de.markusfisch.android.shadereditor.editor.Document.Selection;

/**
 * The commands that keys and gestures map to, applied to a document.
 * A move with extend keeps the anchor of the selection and moves its
 * head; without, the selection collapses to where the head goes. Moves
 * up and down aim at the column the caret had before the first of
 * them, so the caret returns to it after a shorter line.
 */
public final class EditCommands {
	private final Document document;
	private int tabWidth = 4;
	// The column of a run of vertical moves, while the head is where
	// the last of them put it.
	private int wantedColumn;
	private int wantedHead = -1;
	private int wantedRevision;

	public EditCommands(Document document) {
		this.document = document;
	}

	/** Set the number of columns between two tab stops. */
	public void setTabWidth(int tabWidth) {
		this.tabWidth = tabWidth;
	}

	/** Move to the previous character, or to the selection's start. */
	public void moveBackward(boolean extend) {
		Selection selection = document.selection();
		moveTo(extend || selection.isEmpty()
				? Motion.previousChar(document.text(), selection.head())
				: selection.start(), extend);
	}

	/** Move to the next character, or to the selection's end. */
	public void moveForward(boolean extend) {
		Selection selection = document.selection();
		moveTo(extend || selection.isEmpty()
				? Motion.nextChar(document.text(), selection.head())
				: selection.end(), extend);
	}

	public void moveWordBackward(boolean extend) {
		moveTo(Motion.previousWord(document.text(), head()), extend);
	}

	public void moveWordForward(boolean extend) {
		moveTo(Motion.nextWord(document.text(), head()), extend);
	}

	/**
	 * Move count lines down, or up if count is negative; past the first
	 * line to the start of the text, past the last to its end.
	 */
	public void moveLines(int count, boolean extend) {
		Text text = document.text();
		int head = head();
		int column = head == wantedHead &&
				document.revision() == wantedRevision
				? wantedColumn
				: Motion.column(text, head, tabWidth);
		int line = text.lineOf(head) + count;
		int target;
		if (line < 0) {
			target = 0;
		} else if (line >= text.lineCount()) {
			target = text.length();
		} else {
			target = Motion.offsetAt(text, line, column, tabWidth);
		}
		moveTo(target, extend);
		wantedColumn = column;
		wantedHead = target;
		wantedRevision = document.revision();
	}

	public void moveToLineStart(boolean extend) {
		moveTo(Motion.lineStart(document.text(), head()), extend);
	}

	public void moveToLineEnd(boolean extend) {
		moveTo(Motion.lineEnd(document.text(), head()), extend);
	}

	public void moveToStart(boolean extend) {
		moveTo(0, extend);
	}

	public void moveToEnd(boolean extend) {
		moveTo(document.text().length(), extend);
	}

	public void selectAll() {
		document.select(0, document.text().length());
	}

	/** Select the word at offset, as Motion.wordAt finds it. */
	public void selectWord(int offset) {
		Selection word = Motion.wordAt(document.text(), offset);
		document.select(word.anchor(), word.head());
	}

	/** Select the line of offset with its line break. */
	public void selectLine(int offset) {
		Text text = document.text();
		int line = text.lineOf(offset);
		document.select(text.lineStart(line), line + 1 < text.lineCount()
				? text.lineStart(line + 1)
				: text.length());
	}

	private int head() {
		return document.selection().head();
	}

	private void moveTo(int offset, boolean extend) {
		document.select(extend ? document.selection().anchor() : offset,
				offset);
	}
}
