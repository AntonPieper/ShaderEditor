package de.markusfisch.android.shadereditor.widget;

import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.Motion;
import de.markusfisch.android.shadereditor.editor.Text;

/**
 * What a touch selects in a CodeView: the offset a point of a line
 * means, and what a drag from where it started selects. A caret
 * goes where it is dragged; a drag by character moves the head and
 * keeps the anchor; a drag by word keeps the word it started on and
 * extends to whole words, as Motion finds them. Offsets count into
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

	private final Motion motion;
	private int unit;
	private int anchor;
	private int head;
	// The word the drag started on, and the offset it reached last.
	private int wordStart;
	private int wordEnd;
	private int reached;

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
	 * Start a drag of the end head of the selection from anchor, or of
	 * the caret if they are equal.
	 */
	void dragChars(int anchor, int head) {
		unit = anchor == head ? CARET : CHARS;
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
	 * character, the head goes to offset unless that would make the
	 * selection empty. By word, the selection spans the start word and
	 * the word at offset.
	 */
	boolean dragTo(Text text, int offset) {
		if (offset == reached) {
			return false;
		}
		reached = offset;
		int oldAnchor = anchor;
		int oldHead = head;
		if (unit == CARET) {
			anchor = head = offset;
		} else if (unit == CHARS) {
			if (offset != anchor) {
				head = offset;
			}
		} else if (offset >= wordStart) {
			anchor = wordStart;
			head = Math.max(wordEnd, motion.wordAt(text, offset).end());
		} else {
			anchor = wordEnd;
			head = motion.wordAt(text, offset).start();
		}
		return anchor != oldAnchor || head != oldHead;
	}

	int anchor() {
		return anchor;
	}

	int head() {
		return head;
	}
}
