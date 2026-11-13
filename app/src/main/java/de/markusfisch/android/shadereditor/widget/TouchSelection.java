package de.markusfisch.android.shadereditor.widget;

import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.Motion;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What a touch or a mouse selects in a CodeView: the offset a point of
 * a line means, and what a drag from where it started selects. A caret
 * goes where it is dragged; a drag by character moves the head and
 * keeps the anchor; a drag by word keeps the word it started on and
 * extends to whole words, as Motion finds them. A handle moves the head
 * as the platform's handles do: while the selection grows, a word joins
 * whole once the finger is past its middle; after the handle was moved
 * back into a word, it goes by character, keeping its distance to the
 * finger, until it is at the end of a word again. Offsets count into
 * the whole text; the work is that of one line, and a drag allocates
 * only when the selection changes.
 */
final class TouchSelection {
	/** Where the offsets of one line are, as its layout knows. */
	interface Line {
		float xOf(int offset);

		/** Return the offset between two characters nearest x. */
		int offsetAt(float x);
	}

	private static final int CARET = 0;
	private static final int CHARS = 1;
	private static final int WORDS = 2;
	private static final int HANDLE = 3;

	private final Motion motion;
	private int unit;
	private int anchor;
	private int head;
	// The word the drag started on, and the offset it reached last.
	private int wordStart;
	private int wordEnd;
	private int reached;
	// From the offset of the finger to the head a handle is at.
	private int delta;

	TouchSelection(Motion motion) {
		this.motion = motion;
	}

	/**
	 * Return the offset nearest x in line n of text, as layout places it,
	 * moved to the nearer end of the character it falls in.
	 */
	int offsetAt(Text text, int n, float x, Line layout) {
		int start = text.lineStart(n);
		int length = text.line(n).length();
		int i = layout.offsetAt(x);
		if (i <= 0 || i >= length) {
			return start + Math.max(0, Math.min(i, length));
		}
		int before = motion.previousChar(text, start + i) - start;
		int after = motion.nextChar(text, start + before) - start;
		if (after == i) {
			return start + i;
		}
		return start + (x - layout.xOf(before) <= layout.xOf(after) - x
				? before
				: after);
	}

	/** Return the word a double tap or a long press at offset selects. */
	Selection wordAt(Text text, int offset) {
		return motion.wordAt(text, offset);
	}

	/**
	 * Start a drag of the handle of the end head of the selection from
	 * anchor, or of the caret's if they are equal.
	 */
	void dragHandle(int anchor, int head) {
		dragChars(anchor, head);
		unit = anchor == head ? CARET : HANDLE;
		delta = 0;
	}

	/** Start a drag by character from anchor, the head at head. */
	void dragChars(int anchor, int head) {
		unit = CHARS;
		this.anchor = anchor;
		this.head = head;
		reached = head;
	}

	/** Start a drag by word from the word at offset, and select it. */
	void dragWords(Text text, int offset) {
		Selection word = motion.wordAt(text, offset);
		unit = WORDS;
		anchor = wordStart = word.start();
		head = wordEnd = word.end();
		reached = offset;
	}

	/**
	 * Drag to offset; return whether the selection changed. By
	 * character, the head goes to offset; a handle never makes the
	 * selection empty. By word, the selection spans the start word and
	 * the word at offset.
	 */
	boolean dragTo(Text text, int offset) {
		if (offset == reached) {
			return false;
		}
		int from = reached;
		reached = offset;
		int oldAnchor = anchor;
		int oldHead = head;
		if (unit == CARET) {
			anchor = head = offset;
		} else if (unit == CHARS) {
			head = offset;
		} else if (unit == HANDLE) {
			moveHandle(text, from, offset);
		} else if (offset >= wordStart) {
			anchor = wordStart;
			head = Math.max(wordEnd, motion.wordAt(text, offset).end());
		} else {
			anchor = wordEnd;
			head = motion.wordAt(text, offset).start();
		}
		return anchor != oldAnchor || head != oldHead;
	}

	// Moves the head of a handle the finger moved from one offset to
	// another.
	private void moveHandle(Text text, int from, int offset) {
		int direction = head > anchor ? 1 : -1;
		boolean grows = (offset - from) * direction > 0;
		int line = text.lineOf(offset);
		boolean otherLine = line != text.lineOf(head);
		int to;
		if (otherLine || (grows && !inWord(text, head))) {
			to = snap(text, offset, direction);
		} else {
			int start = text.lineStart(line);
			to = Math.max(start, Math.min(offset + delta,
					start + text.line(line).length()));
			// Not inside a character.
			to = motion.previousChar(text, motion.nextChar(text, to));
		}
		if (to != anchor && (to - head) * direction * (grows ? 1 : -1) > 0) {
			head = to;
		}
		delta = otherLine ? 0 : head - offset;
	}

	// Returns the end of the word at offset towards direction if offset
	// is past its middle, else its other end; offset if it is not inside
	// a word.
	private int snap(Text text, int offset, int direction) {
		Selection word = motion.wordAt(text, offset);
		if (!inWord(text, word, offset)) {
			return offset;
		}
		int middle = word.start() + (word.end() - word.start()) / 2;
		return direction > 0
				? offset >= middle ? word.end() : word.start()
				: offset <= middle ? word.start() : word.end();
	}

	private boolean inWord(Text text, int offset) {
		return inWord(text, motion.wordAt(text, offset), offset);
	}

	// Motion's word is white space if there is no word.
	private static boolean inWord(Text text, Selection word, int offset) {
		if (word.start() >= offset || offset >= word.end()) {
			return false;
		}
		int line = text.lineOf(offset);
		return !Character.isWhitespace(text.line(line).charAt(
				word.start() - text.lineStart(line)));
	}

	int anchor() {
		return anchor;
	}

	int head() {
		return head;
	}
}
