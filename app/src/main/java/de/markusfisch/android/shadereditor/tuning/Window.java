package de.markusfisch.android.shadereditor.tuning;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * The lines of a text around an offset, lexed from the state the first
 * of them starts in: what a number's place and statement need, at a
 * cost that does not grow with the text. Offsets are those of the
 * whole text; outside the lines, a character reads as a line end.
 */
public final class Window implements CharSequence {
	/** The lexer state each line starts in, as GlslLexer.lexLine gives it. */
	public interface Lines {
		/** Return the state line starts in, or -1 when it is not known. */
		int state(int line);
	}

	/** How many lines it has above and below the offset's. */
	static final int LINES = 8;

	@NonNull
	final Tokens tokens;
	@NonNull
	private final String lines;
	private final int base;

	private Window(@NonNull String lines, int base, int state) {
		this.lines = lines;
		this.base = base;
		tokens = GlslLexer.lex(lines, state, base);
	}

	/**
	 * The lines of text around offset, from the states of lines; when
	 * one is not known, the window is the whole text.
	 */
	@NonNull
	public static Window around(@NonNull Text text, int offset,
			@NonNull Lines lines) {
		int line = text.lineOf(offset);
		int first = Math.max(0, line - LINES);
		int last = Math.min(text.lineCount() - 1, line + LINES);
		int state = lines.state(first);
		if (state < 0) {
			first = 0;
			last = text.lineCount() - 1;
			state = GlslLexer.TEXT_START;
		}
		int from = text.lineStart(first);
		int to = text.lineStart(last) + text.line(last).length();
		return new Window(text.substring(from, to), from, state);
	}

	/** The number at offset, as {@link TunedPlace#at} finds it. */
	@Nullable
	public TunedPlace place(int offset) {
		return offset < base || offset > end()
				? null
				: TunedPlace.at(this, tokens, offset);
	}

	// Where the literal of the number nearest offset starts, after it
	// when forward, or -1.
	int number(int offset, boolean forward) {
		int size = tokens.size();
		for (int k = 0; k < size; ++k) {
			int i = forward ? k : size - 1 - k;
			if (tokens.style(i) == Style.NUMBER && (forward
					? tokens.start(i) >= offset
					: tokens.end(i) <= offset) &&
					place(tokens.start(i)) != null) {
				return tokens.start(i);
			}
		}
		return -1;
	}

	int start() {
		return base;
	}

	int end() {
		return base + lines.length();
	}

	@Override
	public int length() {
		return base + lines.length();
	}

	@Override
	public char charAt(int index) {
		int i = index - base;
		return i < 0 || i >= lines.length() ? '\n' : lines.charAt(i);
	}

	@NonNull
	@Override
	public CharSequence subSequence(int start, int end) {
		return lines.substring(start - base, end - base);
	}

	@NonNull
	@Override
	public String toString() {
		return lines;
	}
}
