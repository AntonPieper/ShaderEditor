package de.markusfisch.android.shadereditor.syntax;

/**
 * One line as a {@link Syntax} tokenized it: its text, the state
 * it started in and its tokens, with UTF-16 offsets into the line. It is
 * immutable, so it may be handed to any thread.
 */
public final class LexedLine {
	private static final Style[] STYLES = Style.values();

	private final String text;
	private final int state;
	private final int endState;
	// Start, end and kind of each token.
	private final int[] tokens;

	private LexedLine(String text, int state, int endState, int[] tokens) {
		this.text = text;
		this.state = state;
		this.endState = endState;
		this.tokens = tokens;
	}

	/**
	 * Tokenize line, which starts in state, as syntax does, with scratch
	 * as the lexer's working space.
	 */
	public static LexedLine of(Syntax syntax, String line,
			boolean breakFollows, int state, LineTokens scratch) {
		int endState = syntax.lexLine(line, breakFollows, state, scratch);
		int size = scratch.size();
		int[] tokens = new int[size * 3];
		for (int i = 0; i < size; ++i) {
			tokens[i * 3] = scratch.starts[i];
			tokens[i * 3 + 1] = scratch.ends[i];
			tokens[i * 3 + 2] = scratch.kinds[i];
		}
		return new LexedLine(line, state, endState, tokens);
	}

	/** Return the line, without its line end. */
	public String text() {
		return text;
	}

	/** Return the state the line starts in. */
	public int state() {
		return state;
	}

	/** Return the state the next line starts in. */
	public int endState() {
		return endState;
	}

	public int size() {
		return tokens.length / 3;
	}

	public int start(int index) {
		return tokens[index * 3];
	}

	public int end(int index) {
		return tokens[index * 3 + 1];
	}

	public Style style(int index) {
		return STYLES[tokens[index * 3 + 2] & 0xff];
	}

	/** True if the token belongs to a preprocessor directive. */
	public boolean inDirective(int index) {
		return (tokens[index * 3 + 2] & Tokens.IN_DIRECTIVE) != 0;
	}

	/** Index of the token that contains offset, or -1 if there is none. */
	public int indexAt(int offset) {
		int low = 0;
		int high = size() - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (start(mid) <= offset) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return high >= 0 && offset < end(high) ? high : -1;
	}
}
