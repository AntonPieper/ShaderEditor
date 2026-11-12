package de.markusfisch.android.shadereditor.editor;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Style;

/**
 * The colours of the lines of a document, kept current as it changes.
 * The colours of a line follow from its text and the lexer state it
 * starts in, which is the state the line above ends in; that state is
 * kept for every line, one byte each. After an edit the changed lines
 * are tokenized again, and the lines below them for as long as a line
 * ends in another state than before, but no more than budget lines in
 * all. The lines below that are stale: they keep the states they had
 * until a continuation, which may run on any thread, delivers theirs.
 * Made once per document and used on its thread.
 */
public final class LineStyles {
	// How many changes a continuation may lag behind and still be used.
	private static final int HISTORY = 64;
	private static final int CACHE = 256;
	private static final int BREAK_FOLLOWS = 0x100;
	private static final Style[] STYLES = Style.values();

	private final LineTokens tokens = new LineTokens();
	private final int[] changedLines = new int[HISTORY];
	private final Runs[] cache = new Runs[CACHE];
	private final Document document;
	private final int budget;
	private Set<String> typeNames = Collections.emptySet();
	// The state every line ends in, with a gap at the last edit.
	private byte[] states;
	private int gapStart;
	private int gapEnd;
	private int lines;
	// Every line above staleFrom has its current state, and every line
	// from consistentFrom on has the state its text gives the state
	// stored above it. When staleFrom is the line count, all are current.
	private int staleFrom;
	private int consistentFrom;
	private boolean walkPending;

	/**
	 * Keep the colours of document, tokenizing at most budget lines per
	 * edit: the lines on screen and a margin. The first budget lines are
	 * tokenized now, the rest by continuations.
	 */
	public LineStyles(Document document, int budget) {
		this.document = document;
		this.budget = budget;
		lines = document.text().lineCount();
		states = new byte[lines + 64];
		gapStart = lines;
		gapEnd = states.length;
		consistentFrom = lines;
		walk();
		document.addListener(new Document.Listener() {
			@Override
			public void onChange(Document.Change change, Text before) {
				update(change, before);
			}

			@Override
			public void onUpdate() {
				if (walkPending) {
					walkPending = false;
					walk();
				}
			}
		});
	}

	/** Return the colour runs of line n of the current text. */
	public Runs runs(int n) {
		String line = document.text().line(n);
		int key = startState(n) | (n < lines - 1 ? BREAK_FOLLOWS : 0);
		int slot = (System.identityHashCode(line) * 31 + key) & (CACHE - 1);
		Runs runs = cache[slot];
		if (runs == null || runs.line != line || runs.key != key) {
			runs = lex(line, key);
			cache[slot] = runs;
		}
		return runs;
	}

	/**
	 * Paint every identifier whose text is in names as a type. The set
	 * must not change afterwards. Return whether runs may have changed.
	 */
	public boolean setTypeNames(Set<String> names) {
		if (names.equals(typeNames)) {
			return false;
		}
		typeNames = names;
		Arrays.fill(cache, null);
		return true;
	}

	/**
	 * Return the work that makes the stale lines current, to be run on
	 * any thread and handed to apply, or null if no line is stale.
	 */
	public Continuation continuation() {
		return staleFrom < lines
				? new Continuation(document.text(), document.revision(),
						staleFrom, startState(staleFrom))
				: null;
	}

	/**
	 * Store the states of result, as far as they hold for the current
	 * text: those of the lines above the first line changed since the
	 * continuation was made. A result made more than 64 changes ago, or
	 * from a line that has become stale again, is dropped. Return whether
	 * a state was stored, so lines may need drawing again.
	 */
	public boolean apply(Result result) {
		int revision = document.revision();
		if (revision - result.revision > HISTORY ||
				result.from > staleFrom ||
				startState(result.from) != result.state) {
			return false;
		}
		int end = result.from + result.states.length;
		for (int r = result.revision; r < revision; ) {
			end = Math.min(end, changedLines[++r % HISTORY]);
		}
		boolean stored = false;
		while (staleFrom < end) {
			settle(result.states[staleFrom - result.from] & 0xff);
			stored = true;
		}
		return stored;
	}

	// The first line whose state may not be current, or the line count.
	int staleFrom() {
		return staleFrom;
	}

	int startState(int line) {
		return line == 0 ? GlslLexer.TEXT_START : get(line - 1);
	}

	private void update(Document.Change change, Text before) {
		int first = before.lineOf(change.start());
		int removed = change.removed().lineCount() - 1;
		int inserted = change.inserted().lineCount() - 1;
		int last = first + inserted;
		int oldEnd = get(first + removed);
		changedLines[document.revision() % HISTORY] = first;
		replace(first, removed + 1, inserted + 1);
		// Until they are tokenized, the new lines pass on what the old
		// ones did, so the lines below them keep their colours.
		Arrays.fill(states, gapStart - inserted - 1, gapStart, (byte) oldEnd);
		boolean current = staleFrom == lines;
		lines += inserted - removed;
		consistentFrom = !current && consistentFrom > first + removed
				? consistentFrom + inserted - removed
				: last + 1;
		if (first <= staleFrom) {
			staleFrom = first;
			walkPending = true;
		}
	}

	private void walk() {
		Text text = document.text();
		int state = startState(staleFrom);
		for (int n = budget; staleFrom < lines && n-- > 0; ) {
			state = GlslLexer.lexLine(text.line(staleFrom),
					staleFrom < lines - 1, state, tokens);
			settle(state);
		}
	}

	// Stores the state of line staleFrom, whose start state is current.
	private void settle(int state) {
		int line = staleFrom;
		int old = get(line);
		set(line, state);
		if (state == old && line + 1 >= consistentFrom) {
			staleFrom = lines;
		} else {
			staleFrom = line + 1;
			consistentFrom = Math.max(consistentFrom, line + 2);
		}
	}

	private Runs lex(String line, int key) {
		GlslLexer.lexLine(line, (key & BREAK_FOLLOWS) != 0, key & 0xff,
				tokens);
		int[] runs = new int[tokens.size() * 3];
		int size = 0;
		for (int i = 0, n = tokens.size(); i < n; ++i) {
			int start = tokens.start(i);
			int end = tokens.end(i);
			Style style = tokens.style(i);
			if (style == Style.IDENTIFIER && !typeNames.isEmpty() &&
					typeNames.contains(line.substring(start, end))) {
				style = Style.TYPE;
			}
			// What lies between two tokens of one style joins their run.
			if (size > 0 && runs[size - 1] == style.ordinal()) {
				runs[size - 2] = end;
			} else {
				runs[size++] = start;
				runs[size++] = end;
				runs[size++] = style.ordinal();
			}
		}
		return new Runs(line, key, Arrays.copyOf(runs, size));
	}

	private int get(int line) {
		return states[line < gapStart ? line : line + gapEnd - gapStart] &
				0xff;
	}

	private void set(int line, int state) {
		states[line < gapStart ? line : line + gapEnd - gapStart] =
				(byte) state;
	}

	// Replaces the states of remove lines at line by insert states; the
	// inserted ones end right before the gap.
	private void replace(int line, int remove, int insert) {
		moveGap(line);
		gapEnd += remove;
		if (gapEnd - gapStart < insert) {
			int tail = states.length - gapEnd;
			byte[] grown = new byte[(gapStart + tail + insert) * 2 + 64];
			System.arraycopy(states, 0, grown, 0, gapStart);
			System.arraycopy(states, gapEnd, grown, grown.length - tail, tail);
			gapEnd = grown.length - tail;
			states = grown;
		}
		gapStart += insert;
	}

	private void moveGap(int to) {
		if (to < gapStart) {
			int n = gapStart - to;
			System.arraycopy(states, to, states, gapEnd - n, n);
			gapStart -= n;
			gapEnd -= n;
		} else if (to > gapStart) {
			int n = to - gapStart;
			System.arraycopy(states, gapEnd, states, gapStart, n);
			gapStart += n;
			gapEnd += n;
		}
	}

	/**
	 * The colour runs of one line, in order, with offsets into the line.
	 * What lies between two runs is white space.
	 */
	public static final class Runs {
		private final String line;
		private final int key;
		private final int[] runs;

		private Runs(String line, int key, int[] runs) {
			this.line = line;
			this.key = key;
			this.runs = runs;
		}

		public int size() {
			return runs.length / 3;
		}

		public int start(int index) {
			return runs[index * 3];
		}

		public int end(int index) {
			return runs[index * 3 + 1];
		}

		public Style style(int index) {
			return STYLES[runs[index * 3 + 2]];
		}
	}

	/**
	 * Tokenizing the stale lines of one text, from the first on. It
	 * holds only immutable values, so it may run on any thread.
	 */
	public static final class Continuation {
		private final Text text;
		private final int revision;
		private final int from;
		private final int state;

		private Continuation(Text text, int revision, int from, int state) {
			this.text = text;
			this.revision = revision;
			this.from = from;
			this.state = state;
		}

		/**
		 * Return the states of at most maxLines lines. The thread that
		 * applies the result does work in proportion to its lines.
		 */
		public Result run(int maxLines) {
			int lines = text.lineCount();
			int end = (int) Math.min(lines, (long) from + maxLines);
			byte[] states = new byte[end - from];
			LineTokens tokens = new LineTokens();
			for (int line = from, s = state; line < end; ++line) {
				s = GlslLexer.lexLine(text.line(line), line < lines - 1, s,
						tokens);
				states[line - from] = (byte) s;
			}
			return new Result(revision, from, state, states);
		}
	}

	/** The states a continuation computed, for the revision it ran on. */
	public static final class Result {
		private final int revision;
		private final int from;
		private final int state;
		private final byte[] states;

		private Result(int revision, int from, int state, byte[] states) {
			this.revision = revision;
			this.from = from;
			this.state = state;
			this.states = states;
		}
	}
}
