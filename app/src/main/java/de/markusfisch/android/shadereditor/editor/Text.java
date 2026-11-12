package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable text, kept as a balanced tree of lines: a leaf holds up
 * to 32 lines, a node about 32 children and the length and line count
 * of its subtree. A text has at least one line; n lines are joined by
 * n - 1 line breaks of one character each. Since a text never
 * changes, it can be read on another thread as it is. Finding a line,
 * or the line of an offset, is one walk from the root to a leaf. This
 * is the document tree of CodeMirror 6.
 */
public abstract class Text {
	public static final Text EMPTY = new Leaf(new String[]{""});

	private static final int BRANCH = 32;

	private final int length;
	private final int lines;

	private Text(int length, int lines) {
		this.length = length;
		this.lines = lines;
	}

	/**
	 * Return the text of s. A line ends at "\r\n", at a lone '\r' and
	 * at '\n', and a no-break space (U+00A0) becomes a space, so no text
	 * holds a '\r' or a no-break space.
	 */
	public static Text of(CharSequence s) {
		String string = s.toString();
		if (string.indexOf('\r') > -1) {
			string = string.replace("\r\n", "\n").replace('\r', '\n');
		}
		string = string.replace('\u00a0', ' ');
		List<String> lines = new ArrayList<>();
		int start = 0;
		for (int end; (end = string.indexOf('\n', start)) > -1;
				start = end + 1) {
			lines.add(string.substring(start, end));
		}
		lines.add(string.substring(start));
		return ofLines(lines);
	}

	/** Return the number of characters, line breaks included. */
	public final int length() {
		return length;
	}

	/** Return the number of lines, at least 1. */
	public final int lineCount() {
		return lines;
	}

	/** Return line n, counted from 0, without its line break. */
	public final String line(int n) {
		checkIndex(n, lines - 1);
		return lineAt(n);
	}

	/** Return the offset of the first character of line n. */
	public final int lineStart(int n) {
		checkIndex(n, lines - 1);
		return startOf(n);
	}

	/**
	 * Return the line that holds offset. A line break belongs to the
	 * line it ends, and the offset length to the last line.
	 */
	public final int lineOf(int offset) {
		checkIndex(offset, length);
		return indexAt(offset);
	}

	/** Return the range [from, to) as a string. */
	public final String substring(int from, int to) {
		checkRange(from, to);
		StringBuilder sb = new StringBuilder(to - from);
		appendTo(sb, from, to);
		return sb.toString();
	}

	@Override
	public final String toString() {
		return substring(0, length);
	}

	abstract String lineAt(int n);

	abstract int startOf(int n);

	abstract int indexAt(int offset);

	abstract void appendTo(StringBuilder sb, int from, int to);

	private void checkRange(int from, int to) {
		checkIndex(to, length);
		checkIndex(from, to);
	}

	private static void checkIndex(int index, int max) {
		if (index < 0 || index > max) {
			throw new IndexOutOfBoundsException(
					index + " is not in [0, " + max + "]");
		}
	}

	private static Text ofLines(List<String> lines) {
		List<Text> level = new ArrayList<>();
		for (int i = 0, size = lines.size(); i < size; i += BRANCH) {
			level.add(new Leaf(lines.subList(i, Math.min(size, i + BRANCH))));
		}
		while (level.size() > 1) {
			List<Text> parents = new ArrayList<>();
			for (int i = 0, size = level.size(); i < size; i += BRANCH) {
				parents.add(Node.of(level.subList(i,
						Math.min(size, i + BRANCH))));
			}
			level = parents;
		}
		return level.get(0);
	}

	private static final class Leaf extends Text {
		private final String[] text;

		private Leaf(String[] text) {
			super(lengthOf(text), text.length);
			this.text = text;
		}

		private Leaf(List<String> lines) {
			this(lines.toArray(new String[0]));
		}

		private static int lengthOf(String[] lines) {
			int length = lines.length - 1;
			for (String line : lines) {
				length += line.length();
			}
			return length;
		}

		@Override
		String lineAt(int n) {
			return text[n];
		}

		@Override
		int startOf(int n) {
			int start = 0;
			for (int i = 0; i < n; ++i) {
				start += text[i].length() + 1;
			}
			return start;
		}

		@Override
		int indexAt(int offset) {
			int i = 0;
			for (int end = text[0].length(); end < offset;
					end += text[i].length() + 1) {
				++i;
			}
			return i;
		}

		@Override
		void appendTo(StringBuilder sb, int from, int to) {
			for (int pos = 0, i = 0; i < text.length && pos <= to; ++i) {
				String line = text[i];
				int end = pos + line.length();
				if (i > 0 && pos > from) {
					sb.append('\n');
				}
				if (from < end && to > pos) {
					sb.append(line, Math.max(0, from - pos),
							Math.min(line.length(), to - pos));
				}
				pos = end + 1;
			}
		}
	}

	private static final class Node extends Text {
		private final Text[] children;

		private Node(Text[] children, int length, int lines) {
			super(length, lines);
			this.children = children;
		}

		private static Node of(List<Text> children) {
			int length = -1;
			int lines = 0;
			for (Text child : children) {
				length += child.length + 1;
				lines += child.lines;
			}
			return new Node(children.toArray(new Text[0]), length, lines);
		}

		@Override
		String lineAt(int n) {
			int i = 0;
			for (; n >= children[i].lines; ++i) {
				n -= children[i].lines;
			}
			return children[i].lineAt(n);
		}

		@Override
		int startOf(int n) {
			int start = 0;
			int i = 0;
			for (; n >= children[i].lines; ++i) {
				n -= children[i].lines;
				start += children[i].length + 1;
			}
			return start + children[i].startOf(n);
		}

		@Override
		int indexAt(int offset) {
			int line = 0;
			int i = 0;
			for (; offset > children[i].length; ++i) {
				offset -= children[i].length + 1;
				line += children[i].lines;
			}
			return line + children[i].indexAt(offset);
		}

		@Override
		void appendTo(StringBuilder sb, int from, int to) {
			for (int i = 0, pos = 0; i < children.length && pos <= to; ++i) {
				Text child = children[i];
				int end = pos + child.length;
				if (i > 0 && pos > from) {
					sb.append('\n');
				}
				if (from < end && to > pos) {
					child.appendTo(sb, from - pos, to - pos);
				}
				pos = end + 1;
			}
		}
	}
}
