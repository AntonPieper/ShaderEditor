package de.markusfisch.android.shadereditor.editor;

import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.TextInput.Window;

/**
 * Writes what keyboards and the app did to documents through TextInput
 * as lines of text, so a session on a device can be replayed on the
 * JVM. A line is the milliseconds since the session started, who acted
 * (the number of a connection, or app), a name and its arguments, all
 * separated by spaces. A string is in double quotes with the escapes
 * \\, \", \n, \t, and a backslash, u and four hex digits as in Java for
 * any other character that is not printable ASCII; a missing string is
 * null. A line that starts with # is a comment.
 * <pre>
 * 0 1 open 4 4 "x = ;"         connection 1 opened with this selection
 *                              (anchor, head) on a document with this
 *                              text, or on null: the document of the
 *                              connection that opened before it
 * 90 1 commitText "v" 1 -> true
 *                              a call of the keyboard and its answer;
 *                              a window is offset, selection, text
 * 90 1 updateSelection 5 5 -1 -1
 *                              what the keyboard was told: selection
 *                              and composing region,
 * 90 1 updateExtractedText 3 0 5 5 "x = v;"
 *                              or a monitored window under its token
 * 400 app key 0 67 0           what the app was asked to do, before it
 *                              did it: a key event (action, key code,
 *                              meta state), undo, redo, select, a
 *                              context menu action, a command
 * 400 1 changed 4 5 ""         a change the connection did not make,
 * 400 1 selected 4 4           and a selection
 * # 410 requestCursorUpdates 2 -> false
 *                              a call TextInput does not take
 * 500 1 close -> 4 4 "x = ;"   the connection closed on this selection
 *                              and text
 * 510 - truncated              the session reached MAX_SIZE
 * </pre>
 * A session replays when its opens, calls, actions and closes, made
 * again with a clock at the time of each line, write the same lines
 * again, times aside. Lines collect in memory until drain takes them;
 * the app drains a session from time to time and writes the lines out
 * on another thread. A session holds at most MAX_SIZE characters of
 * lines, texts included: at that bound it writes truncated, records
 * nothing more, and a replay ends there.
 */
public final class KeyboardSession {
	public static final int MAX_SIZE = 1 << 20;

	// Records nothing.
	static final KeyboardSession NONE = new KeyboardSession(null, 0);

	private final StringBuilder lines = new StringBuilder();
	private final Document.Clock clock;
	private final int maxSize;
	private final long start;
	private boolean stopped;
	private int size;
	private int connections;
	// The document of the connection that opened last.
	private Document document;

	public KeyboardSession() {
		this(() -> System.nanoTime() / 1_000_000, MAX_SIZE);
	}

	KeyboardSession(Document.Clock clock, int maxSize) {
		this.clock = clock;
		this.maxSize = maxSize;
		stopped = clock == null;
		start = stopped ? 0 : clock.millis();
	}

	/**
	 * Write what the app is asked to do, before it does it: undo, redo,
	 * select with anchor and head, a key event or a context menu action
	 * the view handles, or a command, each with its arguments.
	 */
	public void action(String name, Object... args) {
		if (!stopped) {
			write("", "app " + format(name, args));
		}
	}

	/** Write a call that TextInput does not take, as a comment. */
	public void note(String name, Object answer, Object... args) {
		if (!stopped) {
			write("# ", format(name, args) + " -> " + value(answer));
		}
	}

	/** Return the lines written since the last drain, and forget them. */
	public String drain() {
		String drained = lines.toString();
		lines.setLength(0);
		return drained;
	}

	// Returns the number of a new connection to document.
	int open(Document document, Selection selection) {
		if (stopped) {
			return 0;
		}
		Text text = document != this.document ? document.text() : null;
		this.document = document;
		int connection = ++connections;
		if (fits(text)) {
			write("", connection + " open " + selection.anchor() + " " +
					selection.head() + " " +
					(text != null ? quote(text.toString()) : "null"));
		}
		return connection;
	}

	void call(int connection, String name, Object[] args, Object answer) {
		if (!stopped) {
			write("", connection + " " + format(name, args) + " -> " +
					value(answer));
		}
	}

	void updateSelection(int connection, int selStart, int selEnd,
			int composingStart, int composingEnd) {
		if (!stopped) {
			write("", connection + " updateSelection " + selStart + " " +
					selEnd + " " + composingStart + " " + composingEnd);
		}
	}

	void updateExtractedText(int connection, int token, Window window) {
		if (!stopped) {
			write("", connection + " updateExtractedText " + token + " " +
					value(window));
		}
	}

	void changed(int connection, int from, int to, Text text) {
		if (!stopped && fits(text)) {
			write("", connection + " changed " + from + " " + to + " " +
					quote(text.toString()));
		}
	}

	void selected(int connection, Selection selection) {
		if (!stopped) {
			write("", connection + " selected " + selection.anchor() + " " +
					selection.head());
		}
	}

	void close(int connection, Text text, Selection selection) {
		if (!stopped && fits(text)) {
			write("", connection + " close -> " + selection.anchor() + " " +
					selection.head() + " " + quote(text.toString()));
		}
	}

	// Returns whether the session has room for text; ends it if not,
	// before text is copied.
	private boolean fits(Text text) {
		if (text != null && text.length() > maxSize - size) {
			truncate();
			return false;
		}
		return true;
	}

	private void write(String prefix, String line) {
		if (line.length() > maxSize - size) {
			truncate();
			return;
		}
		size += line.length();
		lines.append(prefix).append(clock.millis() - start).append(' ')
				.append(line).append('\n');
	}

	private void truncate() {
		lines.append(clock.millis() - start).append(" - truncated\n");
		stopped = true;
	}

	private static String format(String name, Object[] args) {
		StringBuilder sb = new StringBuilder(name);
		for (Object arg : args) {
			sb.append(' ').append(value(arg));
		}
		return sb.toString();
	}

	private static String value(Object value) {
		if (value instanceof CharSequence s) {
			return quote(s);
		} else if (value instanceof Window w) {
			return w.offset() + " " + w.selectionStart() + " " +
					w.selectionEnd() + " " + quote(w.text());
		}
		return String.valueOf(value);
	}

	private static String quote(CharSequence s) {
		StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
		for (int i = 0, length = s.length(); i < length; ++i) {
			char c = s.charAt(i);
			if (c == '"' || c == '\\') {
				sb.append('\\').append(c);
			} else if (c == '\n') {
				sb.append("\\n");
			} else if (c == '\t') {
				sb.append("\\t");
			} else if (c < ' ' || c > '~') {
				sb.append("\\u");
				for (int shift = 12; shift >= 0; shift -= 4) {
					sb.append(Character.forDigit((c >> shift) & 15, 16));
				}
			} else {
				sb.append(c);
			}
		}
		return sb.append('"').toString();
	}
}
