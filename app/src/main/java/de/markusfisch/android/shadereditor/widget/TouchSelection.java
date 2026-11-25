package de.markusfisch.android.shadereditor.widget;

import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.editor.Document.Change;
import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.Motion;
import de.markusfisch.android.shadereditor.editor.Text;

// What a touch or a mouse selects in a CodeView. A drag by word keeps the word
// it started on and extends by whole words. A handle moves the head as the
// platform's handles do: while the selection grows, a word joins whole once the
// finger is past its middle; after the handle was moved back into a word, it
// goes by character, keeping its distance to the finger, until it is at the end
// of a word again. While a drag by word is in progress the handles do not show,
// as the platform's do not. A change to the text moves the offsets as the
// document moves its selection, so a drag goes on in the text the change left.
// A drag allocates only when the selection changes.
final class TouchSelection {
	interface Line {
		float xOf(int offset);

		int offsetAt(float x);
	}

	private static final int CARET = 0;
	private static final int CHARS = 1;
	private static final int WORDS = 2;
	private static final int HANDLE = 3;
	// As Editor's DRAG_SHADOW_MAX_TEXT_LENGTH.
	private static final int SHADOW_LENGTH = 20;

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

	// Moved to the nearer end of the character x falls in.
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

	Selection wordAt(Text text, int offset) {
		return motion.wordAt(text, offset);
	}

	// Of the head, or of the caret if anchor and head are equal.
	void dragHandle(int anchor, int head) {
		dragChars(anchor, head);
		unit = anchor == head ? CARET : HANDLE;
		delta = 0;
	}

	void dragChars(int anchor, int head) {
		unit = CHARS;
		this.anchor = anchor;
		this.head = head;
		reached = head;
	}

	void dragWords(Text text, int offset) {
		Selection word = motion.wordAt(text, offset);
		unit = WORDS;
		anchor = wordStart = word.start();
		head = wordEnd = word.end();
		reached = offset;
	}

	// Returns whether the selection changed. A handle never makes the selection
	// empty; by word, the selection spans the start word and the word at
	// offset.
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
			int end = start + text.line(line).length();
			to = Math.max(start, Math.min(offset + delta, end));
			// Not inside a character; the line's end is none.
			if (to < end) {
				to = motion.previousChar(text, motion.nextChar(text, to));
			}
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

	// As Change.map moves the document's selection.
	void map(Change change) {
		anchor = change.map(anchor);
		head = change.map(head);
		wordStart = change.map(wordStart);
		wordEnd = change.map(wordEnd);
		reached = change.map(reached);
	}

	// touched while it is the document's selection and the handles show at all,
	// else null. Every change to the text replaces the document's selection,
	// also inside a batch, so the handles never show at offsets of a text that
	// is gone.
	@Nullable
	Selection handles(@Nullable Selection touched, Selection current,
			boolean dragging) {
		return touched != null && touched == current &&
				showsHandles(dragging) ? current : null;
	}

	// Not while the drag started last is by word or by character and in
	// progress; then only the handle being dragged.
	boolean showsHandles(boolean dragging) {
		return !dragging || unit == HANDLE || unit == CARET;
	}

	// x kept inside the text, or its part beyond the other end of the
	// selection, as the platform's text views place it; NaN, which hides the
	// magnifier, if the finger is more than half the magnifier's width away
	// from it.
	static float magnifierX(float x, float left, float right,
			float halfWidth) {
		if (x < left - halfWidth || x > right + halfWidth) {
			return Float.NaN;
		}
		return Math.max(left, Math.min(right, x));
	}

	// Counted after the text was taken out; -1 if the drop is inside it, which
	// moves nothing.
	static int dropAt(int start, int end, int offset) {
		if (offset >= start && offset <= end) {
			return -1;
		}
		return offset > end ? offset - (end - start) : offset;
	}

	// Its first line that is not empty, cut after SHADOW_LENGTH characters and
	// the one after them without an ellipsis, as the platform's text views do.
	Selection shadowOf(Text text, int start, int end) {
		int from = start;
		while (from < end && from == motion.lineEnd(text, from)) {
			++from;
		}
		int to = Math.min(end, motion.lineEnd(text, from));
		if (to - from > SHADOW_LENGTH) {
			to = Math.min(to, motion.nextChar(text, from + SHADOW_LENGTH));
		}
		return new Selection(from, to);
	}

	int anchor() {
		return anchor;
	}

	int head() {
		return head;
	}
}
