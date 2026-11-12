package de.markusfisch.android.shadereditor.editor;

import java.io.Flushable;
import java.io.IOException;

import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.TextInput.Window;

/**
 * Writes what a keyboard did through a TextInput as lines of text, so a
 * session on a device can be replayed on the JVM. A line is a name and
 * its arguments separated by spaces. A string is in double quotes with
 * the escapes \\, \", \n, \t, and a backslash, u and four hex digits as
 * in Java for any other character that is not printable ASCII; a
 * missing string is null. A line that starts with # is a comment.
 * <pre>
 * start 4 4 "x = ;"            text and selection (anchor, head) when
 *                              the connection opened
 * commitText "v" 1 -> true     a call of the keyboard and its answer;
 *                              a window is offset, selection, text
 * updateSelection 5 5 -1 -1    what the keyboard was told: selection
 *                              and composing region,
 * updateExtractedText 3 0 5 5 "x = v;"
 *                              or a monitored window under its token
 * app replace 4 5 ""           a change the app made: a key the view
 * app select 4 4               handled, undo, a touch
 * # sendKeyEvent 0 67 -> true  a call TextInput does not take
 * end 4 4 "x = ;"              text and selection when it closed
 * </pre>
 * A session replays when the same calls and app changes, made to a new
 * TextInput on its start text, write the same lines again. Start and
 * end hold the whole text, so a session is best recorded on a small one.
 */
public final class KeyboardSession {
	private Appendable out;

	/** Write to out; a failed write ends the session. */
	public KeyboardSession(Appendable out) {
		this.out = out;
	}

	/** Write a call that TextInput does not take, as a comment. */
	public void note(String name, Object answer, Object... args) {
		if (out != null) {
			write("# " + format(name, args, answer));
		}
	}

	void start(Text text, Selection selection) {
		if (out != null) {
			write("start " + selection.anchor() + " " + selection.head() +
					" " + quote(text.toString()));
		}
	}

	void call(String name, Object[] args, Object answer) {
		if (out != null) {
			write(format(name, args, answer));
		}
	}

	void updateSelection(int selStart, int selEnd, int composingStart,
			int composingEnd) {
		if (out != null) {
			write("updateSelection " + selStart + " " + selEnd + " " +
					composingStart + " " + composingEnd);
		}
	}

	void updateExtractedText(int token, Window window) {
		if (out != null) {
			write("updateExtractedText " + token + " " + value(window));
		}
	}

	void replaced(int from, int to, Text text) {
		if (out != null) {
			write("app replace " + from + " " + to + " " +
					quote(text.toString()));
		}
	}

	void selected(Selection selection) {
		if (out != null) {
			write("app select " + selection.anchor() + " " +
					selection.head());
		}
	}

	void end(Text text, Selection selection) {
		if (out != null) {
			write("end " + selection.anchor() + " " + selection.head() +
					" " + quote(text.toString()));
		}
	}

	private void write(String line) {
		try {
			out.append(line).append('\n');
			if (out instanceof Flushable flushable) {
				flushable.flush();
			}
		} catch (IOException e) {
			out = null;
		}
	}

	private static String format(String name, Object[] args,
			Object answer) {
		StringBuilder sb = new StringBuilder(name);
		for (Object arg : args) {
			sb.append(' ').append(value(arg));
		}
		return sb.append(" -> ").append(value(answer)).toString();
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
				sb.append("\\u").append(
						Integer.toHexString(0x10000 | c).substring(1));
			} else {
				sb.append(c);
			}
		}
		return sb.append('"').toString();
	}
}
