package de.markusfisch.android.shadereditor.editor;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.LineTokens;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). Not production code.
 * <p>
 * The lexer state at the end of every line, kept up to date as the text
 * changes: after an edit only the changed lines are tokenized again, and
 * the lines below them only until a line ends in the state it ended in
 * before. One byte per line in a gap buffer.
 */
public final class LineStates {
	private final LineTokens scratch = new LineTokens();
	private byte[] ends;
	private int gapStart;
	private int gapEnd;
	// First line whose stored state may be stale, or the line count.
	private int dirtyFrom;

	public LineStates(Text text) {
		int lines = text.lineCount();
		ends = new byte[lines + 64];
		gapStart = lines;
		gapEnd = ends.length;
		dirtyFrom = 0;
		advance(text, Integer.MAX_VALUE);
	}

	private LineStates(LineStates other) {
		ends = other.ends.clone();
		gapStart = other.gapStart;
		gapEnd = other.gapEnd;
		dirtyFrom = other.dirtyFrom;
	}

	/** A copy, as a background thread would get one. */
	public LineStates copy() {
		return new LineStates(this);
	}

	public int size() {
		return ends.length - (gapEnd - gapStart);
	}

	/** State in which line starts. */
	public int startOf(int line) {
		return line == 0 ? GlslLexer.LINE_START : get(line - 1);
	}

	public int dirtyFrom() {
		return dirtyFrom;
	}

	/**
	 * Call after a change that replaced the lines firstLine to
	 * firstLine + removedBreaks by firstLine to firstLine +
	 * insertedBreaks. Tokenizes the changed lines and at most budget
	 * lines below them.
	 *
	 * @return the number of lines tokenized
	 */
	public int update(Text text, int firstLine, int removedBreaks,
			int insertedBreaks, int budget) {
		int oldEnd = get(firstLine + removedBreaks);
		replace(firstLine, removedBreaks + 1, insertedBreaks + 1);
		int lastChanged = firstLine + insertedBreaks;
		if (dirtyFrom <= firstLine) {
			// Everything from the edit on is stale anyway; keep it so.
			return 0;
		}
		if (dirtyFrom > firstLine + removedBreaks) {
			dirtyFrom += insertedBreaks - removedBreaks;
		} else {
			dirtyFrom = lastChanged + 1;
		}
		int lines = text.lineCount();
		int state = startOf(firstLine);
		int count = 0;
		int line = firstLine;
		for (; line <= lastChanged; ++line, ++count) {
			if (count >= budget) {
				// A large paste: the rest is tokenized later.
				dirtyFrom = line;
				return count;
			}
			state = GlslLexer.lexLine(text.line(line), line < lines - 1,
					state, scratch);
			set(line, state);
		}
		if (state == oldEnd) {
			return count;
		}
		// The state below the edit changed, as after typing "/*".
		int stale = dirtyFrom;
		dirtyFrom = line;
		int limit = (int) Math.min(Math.min(lines, stale),
				(long) line + budget);
		for (; line < limit; ++line, ++count) {
			int old = get(line);
			state = GlslLexer.lexLine(text.line(line), line < lines - 1,
					state, scratch);
			set(line, state);
			if (state == old) {
				dirtyFrom = stale;
				return count;
			}
		}
		dirtyFrom = line >= stale ? stale : line;
		return count;
	}

	/** Tokenizes up to budget stale lines; returns how many it did. */
	public int advance(Text text, int budget) {
		int lines = text.lineCount();
		int line = dirtyFrom;
		int state = startOf(Math.min(line, lines - 1));
		int count = 0;
		for (; line < lines && count < budget; ++line, ++count) {
			state = GlslLexer.lexLine(text.line(line), line < lines - 1,
					state, scratch);
			set(line, state);
		}
		dirtyFrom = line;
		return count;
	}

	private int get(int line) {
		return ends[line < gapStart ? line : line + gapEnd - gapStart];
	}

	private void set(int line, int state) {
		ends[line < gapStart ? line : line + gapEnd - gapStart] = (byte) state;
	}

	private void replace(int at, int remove, int insert) {
		moveGap(at);
		gapEnd += remove;
		if (gapEnd - gapStart < insert) {
			int size = size();
			byte[] grown = new byte[(size + insert) * 2 + 64];
			System.arraycopy(ends, 0, grown, 0, gapStart);
			int tail = ends.length - gapEnd;
			System.arraycopy(ends, gapEnd, grown, grown.length - tail, tail);
			gapEnd = grown.length - tail;
			ends = grown;
		}
		gapStart += insert;
	}

	private void moveGap(int to) {
		if (to < gapStart) {
			int n = gapStart - to;
			System.arraycopy(ends, to, ends, gapEnd - n, n);
			gapStart -= n;
			gapEnd -= n;
		} else if (to > gapStart) {
			int n = to - gapStart;
			System.arraycopy(ends, gapEnd, ends, gapStart, n);
			gapStart += n;
			gapEnd += n;
		}
	}
}
