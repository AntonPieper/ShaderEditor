package de.markusfisch.android.shadereditor.editor;

import de.markusfisch.android.shadereditor.editor.Document.Selection;

/**
 * The commands that keys and gestures map to, applied to a document.
 * A move with extend keeps the anchor of the selection and moves its
 * head; without, the selection collapses to where the head goes. Moves
 * up and down aim at the column the caret had before the first of
 * them, so the caret returns to it after a shorter line. A delete
 * removes the selection if there is one. Deleting a character, a line
 * break and an indentation typed at the caret are typing, for undo.
 */
public final class EditCommands {
	private final Document document;
	private final Motion motion;
	private int tabWidth = 4;
	private boolean indentWithSpaces;
	// The column of a run of vertical moves, while the head is where
	// the last of them put it.
	private int wantedColumn;
	private int wantedHead = -1;
	private int wantedRevision;

	public EditCommands(Document document) {
		this(document, new Motion());
	}

	public EditCommands(Document document, Motion motion) {
		this.document = document;
		this.motion = motion;
	}

	/** Set the number of columns between two tab stops. */
	public void setTabWidth(int tabWidth) {
		this.tabWidth = tabWidth;
	}

	/**
	 * Set whether a level of indentation is spaces up to the next tab
	 * stop or a tab.
	 */
	public void setIndentWithSpaces(boolean indentWithSpaces) {
		this.indentWithSpaces = indentWithSpaces;
	}

	/** Move to the previous character, or to the selection's start. */
	public void moveBackward(boolean extend) {
		Selection selection = document.selection();
		moveTo(extend || selection.isEmpty()
				? motion.previousChar(document.text(), selection.head())
				: selection.start(), extend);
	}

	/** Move to the next character, or to the selection's end. */
	public void moveForward(boolean extend) {
		Selection selection = document.selection();
		moveTo(extend || selection.isEmpty()
				? motion.nextChar(document.text(), selection.head())
				: selection.end(), extend);
	}

	public void moveWordBackward(boolean extend) {
		moveTo(motion.previousWord(document.text(), head()), extend);
	}

	public void moveWordForward(boolean extend) {
		moveTo(motion.nextWord(document.text(), head()), extend);
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
				: motion.column(text, head, tabWidth);
		int line = text.lineOf(head) + count;
		int target;
		if (line < 0) {
			target = 0;
		} else if (line >= text.lineCount()) {
			target = text.length();
		} else {
			target = motion.offsetAt(text, line, column, tabWidth);
		}
		moveTo(target, extend);
		wantedColumn = column;
		wantedHead = target;
		wantedRevision = document.revision();
	}

	public void moveToLineStart(boolean extend) {
		moveTo(motion.lineStart(document.text(), head()), extend);
	}

	public void moveToLineEnd(boolean extend) {
		moveTo(motion.lineEnd(document.text(), head()), extend);
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
		Selection word = motion.wordAt(document.text(), offset);
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

	public void deleteBackward() {
		int head = head();
		if (!deleteSelection() && head > 0) {
			Text text = document.text();
			int from = motion.previousChar(text, head);
			document.type(from, motion.nextChar(text, from), Text.EMPTY);
		}
	}

	public void deleteForward() {
		int head = head();
		if (!deleteSelection() && head < document.text().length()) {
			Text text = document.text();
			int to = motion.nextChar(text, head);
			document.type(motion.previousChar(text, to), to, Text.EMPTY);
		}
	}

	public void deleteWordBackward() {
		int head = head();
		if (!deleteSelection()) {
			document.replace(motion.previousWord(document.text(), head), head,
					Text.EMPTY);
		}
	}

	public void deleteWordForward() {
		int head = head();
		if (!deleteSelection()) {
			document.replace(head, motion.nextWord(document.text(), head),
					Text.EMPTY);
		}
	}

	/** Replace the selection by a line break and AutoIndent's indent. */
	public void enter() {
		Selection selection = document.selection();
		int start = selection.start();
		int end = selection.end();
		document.type(start, end, Text.of("\n" + AutoIndent.indentAfterBreak(
				document.text(), start, end, level(0))));
	}

	/**
	 * Replace a selection within a line by a level of indentation, or
	 * indent each line of a selection over several lines by a level;
	 * an empty line stays empty.
	 */
	public void indent() {
		Text text = document.text();
		Selection selection = document.selection();
		int start = selection.start();
		if (text.lineOf(start) == text.lineOf(selection.end())) {
			document.type(start, selection.end(), Text.of(level(
					motion.column(text, start, tabWidth))));
			return;
		}
		shiftLines(Text.of(level(0)));
	}

	/**
	 * Remove a level of indentation from each line of the selection:
	 * the white space before the first tab stop.
	 */
	public void unindent() {
		shiftLines(null);
	}

	// Indents each line of the selection by level, or unindents it if
	// level is null. An end of the selection at the start of a line
	// stays there; one in a line moves with its text.
	private void shiftLines(Text level) {
		Text text = document.text();
		Selection selection = document.selection();
		int first = text.lineOf(selection.start());
		int last = text.lineOf(selection.end());
		if (last > first && selection.end() == text.lineStart(last)) {
			--last;
		}
		int anchorLine = text.lineOf(selection.anchor());
		int headLine = text.lineOf(selection.head());
		int anchor = selection.anchor() - text.lineStart(anchorLine);
		int head = selection.head() - text.lineStart(headLine);
		document.beginBatch();
		for (int line = last; line >= first; --line) {
			int start = text.lineStart(line);
			String s = text.line(line);
			int added;
			if (level != null) {
				added = s.isEmpty() ? 0 : level.length();
				document.replace(start, start, added > 0 ? level : Text.EMPTY);
			} else {
				added = -leadingLevel(s);
				document.replace(start, start - added, Text.EMPTY);
			}
			if (line == anchorLine) {
				anchor = shift(anchor, added);
			}
			if (line == headLine) {
				head = shift(head, added);
			}
		}
		Text shifted = document.text();
		document.select(shifted.lineStart(anchorLine) + anchor,
				shifted.lineStart(headLine) + head);
		document.endBatch();
	}

	// Returns the length of the white space before the first tab stop.
	private int leadingLevel(String s) {
		int i = 0;
		for (int column = 0; i < s.length() && column < tabWidth; ++i) {
			char c = s.charAt(i);
			if (c == '\t') {
				column = tabWidth;
			} else if (c == ' ') {
				++column;
			} else {
				break;
			}
		}
		return i;
	}

	private static int shift(int column, int added) {
		return column == 0 ? 0 : Math.max(0, column + added);
	}
	// Returns a level of indentation that starts at column.
	private String level(int column) {
		if (!indentWithSpaces) {
			return "\t";
		}
		int width = Math.max(1, tabWidth);
		StringBuilder sb = new StringBuilder();
		for (int i = width - column % width; i-- > 0; ) {
			sb.append(' ');
		}
		return sb.toString();
	}

	private boolean deleteSelection() {
		Selection selection = document.selection();
		if (selection.isEmpty()) {
			return false;
		}
		document.replace(selection.start(), selection.end(), Text.EMPTY);
		return true;
	}

	private int head() {
		return document.selection().head();
	}

	private void moveTo(int offset, boolean extend) {
		document.select(extend ? document.selection().anchor() : offset,
				offset);
	}
}
