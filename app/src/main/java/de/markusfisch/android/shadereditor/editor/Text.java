package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.List;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). Not production code.
 * <p>
 * Immutable text, stored as a balanced tree of lines. A text has at
 * least one line; n lines hold n - 1 line breaks, each counted as one
 * character ('\n'). Every edit returns a new text that shares all
 * untouched parts with the old one, so a text can be handed to another
 * thread or kept for undo without copying. The shape follows the
 * document tree of CodeMirror 6.
 */
public abstract class Text {
	private static final int BRANCH_SHIFT = 5;
	private static final int BRANCH = 1 << BRANCH_SHIFT;
	private static final int OPEN_FROM = 1;
	private static final int OPEN_TO = 2;

	public static final Text EMPTY = new Leaf(new String[]{""}, 0);

	final int length;
	final int lines;

	private Text(int length, int lines) {
		this.length = length;
		this.lines = lines;
	}

	/** Text of a string; lines end at '\n'. */
	public static Text of(CharSequence s) {
		List<Text> leaves = new ArrayList<>();
		String[] part = new String[BRANCH];
		int count = 0;
		int partLength = -1;
		int length = s.length();
		String string = s.toString();
		int start = 0;
		while (true) {
			int end = string.indexOf('\n', start);
			String line = string.substring(start, end < 0 ? length : end);
			part[count++] = line;
			partLength += line.length() + 1;
			if (count == BRANCH) {
				leaves.add(new Leaf(part, partLength));
				part = new String[BRANCH];
				count = 0;
				partLength = -1;
			}
			if (end < 0) {
				break;
			}
			start = end + 1;
		}
		if (count > 0) {
			String[] rest = new String[count];
			System.arraycopy(part, 0, rest, 0, count);
			leaves.add(new Leaf(rest, partLength));
		}
		return leaves.size() == 1 ? leaves.get(0) : from(leaves, length);
	}

	public final int length() {
		return length;
	}

	public final int lineCount() {
		return lines;
	}

	/** The line with index n (from 0), without its line break. */
	public abstract String line(int n);

	/** Offset of the first character of line n. */
	public abstract int lineStart(int n);

	/** Index of the line that contains offset. */
	public abstract int lineOf(int offset);

	/** Longest line, in characters. An upper bound for the scroll width. */
	public abstract int maxLineLength();

	abstract void decompose(int from, int to, List<Text> target, int open);

	abstract void appendTo(StringBuilder sb, int from, int to);

	public Text replace(int from, int to, Text text) {
		List<Text> parts = new ArrayList<>();
		decompose(0, from, parts, OPEN_TO);
		if (text.length > 0) {
			text.decompose(0, text.length, parts, OPEN_FROM | OPEN_TO);
		}
		decompose(to, length, parts, OPEN_FROM);
		return from(parts, length - (to - from) + text.length);
	}

	public final Text slice(int from, int to) {
		if (from <= 0 && to >= length) {
			return this;
		}
		List<Text> parts = new ArrayList<>();
		decompose(from, to, parts, 0);
		return from(parts, to - from);
	}

	public final String substring(int from, int to) {
		StringBuilder sb = new StringBuilder(to - from);
		appendTo(sb, from, to);
		return sb.toString();
	}

	@Override
	public final String toString() {
		return substring(0, length);
	}

	// Builds a balanced tree from texts that follow each other, each
	// separated from the next by a line break.
	private static Text from(List<Text> children, int length) {
		int lines = 0;
		for (Text child : children) {
			lines += child.lines;
		}
		if (lines < BRANCH) {
			ArrayList<String> flat = new ArrayList<>(lines);
			for (Text child : children) {
				child.flatten(flat);
			}
			return new Leaf(flat.toArray(new String[0]), length);
		}
		Builder builder = new Builder(Math.max(BRANCH, lines >> BRANCH_SHIFT));
		for (Text child : children) {
			builder.add(child);
		}
		builder.flush();
		return builder.chunked.size() == 1
				? builder.chunked.get(0)
				: new Node(builder.chunked.toArray(new Text[0]), length);
	}

	abstract void flatten(List<String> target);

	private static final class Builder {
		private final List<Text> chunked = new ArrayList<>();
		private final List<Text> current = new ArrayList<>();
		private final int chunk;
		private final int maxChunk;
		private final int minChunk;
		private int currentLines;
		private int currentLength = -1;

		private Builder(int chunk) {
			this.chunk = chunk;
			maxChunk = chunk << 1;
			minChunk = chunk >> 1;
		}

		private void add(Text child) {
			Text last;
			if (child.lines > maxChunk && child instanceof Node) {
				for (Text node : ((Node) child).children) {
					add(node);
				}
			} else if (child.lines > minChunk &&
					(currentLines > minChunk || currentLines == 0)) {
				flush();
				chunked.add(child);
			} else if (child instanceof Leaf && currentLines > 0 &&
					(last = current.get(current.size() - 1)) instanceof Leaf &&
					child.lines + last.lines <= BRANCH) {
				currentLines += child.lines;
				currentLength += child.length + 1;
				String[] a = ((Leaf) last).text;
				String[] b = ((Leaf) child).text;
				String[] joined = new String[a.length + b.length];
				System.arraycopy(a, 0, joined, 0, a.length);
				System.arraycopy(b, 0, joined, a.length, b.length);
				current.set(current.size() - 1,
						new Leaf(joined, last.length + 1 + child.length));
			} else {
				if (currentLines + child.lines > chunk) {
					flush();
				}
				currentLines += child.lines;
				currentLength += child.length + 1;
				current.add(child);
			}
		}

		private void flush() {
			if (currentLines == 0) {
				return;
			}
			chunked.add(current.size() == 1
					? current.get(0)
					: from(new ArrayList<>(current), currentLength));
			currentLength = -1;
			currentLines = 0;
			current.clear();
		}
	}

	// Appends the part [from, to] of lines to target; the first appended
	// line continues target's last line.
	private static void appendLines(String[] lines, ArrayList<String> target,
			int from, int to) {
		boolean first = true;
		for (int pos = 0, i = 0; i < lines.length && pos <= to; ++i) {
			String line = lines[i];
			int end = pos + line.length();
			if (end >= from) {
				if (end > to) {
					line = line.substring(0, to - pos);
				}
				if (pos < from) {
					line = line.substring(from - pos);
				}
				if (first) {
					int last = target.size() - 1;
					String head = target.get(last);
					target.set(last, head.isEmpty() ? line : head.concat(line));
					first = false;
				} else {
					target.add(line);
				}
			}
			pos = end + 1;
		}
	}

	private static ArrayList<String> sliceLines(String[] lines, int from,
			int to) {
		ArrayList<String> target = new ArrayList<>(lines.length + 1);
		target.add("");
		appendLines(lines, target, from, to);
		return target;
	}

	private static final class Leaf extends Text {
		private final String[] text;
		private final int maxLine;

		private Leaf(String[] text, int length) {
			super(length, text.length);
			this.text = text;
			int max = 0;
			for (String line : text) {
				max = Math.max(max, line.length());
			}
			maxLine = max;
		}

		@Override
		public String line(int n) {
			return text[n];
		}

		@Override
		public int lineStart(int n) {
			int pos = 0;
			for (int i = 0; i < n; ++i) {
				pos += text[i].length() + 1;
			}
			return pos;
		}

		@Override
		public int lineOf(int offset) {
			int pos = 0;
			for (int i = 0; i < text.length; ++i) {
				pos += text[i].length() + 1;
				if (offset < pos) {
					return i;
				}
			}
			return text.length - 1;
		}

		@Override
		public int maxLineLength() {
			return maxLine;
		}

		@Override
		public Text replace(int from, int to, Text insert) {
			if (!(insert instanceof Leaf)) {
				return super.replace(from, to, insert);
			}
			ArrayList<String> joined = sliceLines(text, 0, from);
			appendLines(((Leaf) insert).text, joined, 0, insert.length);
			appendLines(text, joined, to, length);
			int newLength = length + insert.length - (to - from);
			if (joined.size() <= BRANCH) {
				return new Leaf(joined.toArray(new String[0]), newLength);
			}
			List<Text> parts = new ArrayList<>();
			split(joined, parts);
			return from(parts, newLength);
		}

		private static void split(List<String> lines, List<Text> target) {
			ArrayList<String> part = new ArrayList<>(BRANCH);
			int len = -1;
			for (String line : lines) {
				part.add(line);
				len += line.length() + 1;
				if (part.size() == BRANCH) {
					target.add(new Leaf(part.toArray(new String[0]), len));
					part.clear();
					len = -1;
				}
			}
			if (len > -1) {
				target.add(new Leaf(part.toArray(new String[0]), len));
			}
		}

		@Override
		void decompose(int from, int to, List<Text> target, int open) {
			Leaf part = from <= 0 && to >= length
					? this
					: new Leaf(sliceLines(text, from, to).toArray(new String[0]),
					Math.min(to, length) - Math.max(0, from));
			if ((open & OPEN_FROM) != 0) {
				Leaf prev = (Leaf) target.remove(target.size() - 1);
				ArrayList<String> joined = new ArrayList<>(
						prev.text.length + part.text.length);
				for (String line : prev.text) {
					joined.add(line);
				}
				appendLines(part.text, joined, 0, part.length);
				if (joined.size() <= BRANCH) {
					target.add(new Leaf(joined.toArray(new String[0]),
							prev.length + part.length));
				} else {
					int mid = joined.size() >> 1;
					target.add(leafOf(joined.subList(0, mid)));
					target.add(leafOf(joined.subList(mid, joined.size())));
				}
			} else {
				target.add(part);
			}
		}

		private static Leaf leafOf(List<String> lines) {
			int len = -1;
			for (String line : lines) {
				len += line.length() + 1;
			}
			return new Leaf(lines.toArray(new String[0]), len);
		}

		@Override
		void appendTo(StringBuilder sb, int from, int to) {
			for (int pos = 0, i = 0; i < text.length && pos <= to; ++i) {
				String line = text[i];
				int end = pos + line.length();
				if (end >= from) {
					int a = Math.max(0, from - pos);
					int b = Math.min(line.length(), to - pos);
					if (b > a) {
						sb.append(line, a, b);
					}
					if (end < to && i < text.length - 1) {
						sb.append('\n');
					}
				}
				pos = end + 1;
			}
		}

		@Override
		void flatten(List<String> target) {
			for (String line : text) {
				target.add(line);
			}
		}
	}

	private static final class Node extends Text {
		private final Text[] children;
		private final int maxLine;

		private Node(Text[] children, int length) {
			super(length, countLines(children));
			this.children = children;
			int max = 0;
			for (Text child : children) {
				max = Math.max(max, child.maxLineLength());
			}
			maxLine = max;
		}

		private static int countLines(Text[] children) {
			int lines = 0;
			for (Text child : children) {
				lines += child.lines;
			}
			return lines;
		}

		@Override
		public String line(int n) {
			for (Text child : children) {
				if (n < child.lines) {
					return child.line(n);
				}
				n -= child.lines;
			}
			throw new IndexOutOfBoundsException();
		}

		@Override
		public int lineStart(int n) {
			int pos = 0;
			for (Text child : children) {
				if (n < child.lines) {
					return pos + child.lineStart(n);
				}
				n -= child.lines;
				pos += child.length + 1;
			}
			throw new IndexOutOfBoundsException();
		}

		@Override
		public int lineOf(int offset) {
			int line = 0;
			for (int i = 0; i < children.length; ++i) {
				Text child = children[i];
				if (offset <= child.length || i == children.length - 1) {
					return line + child.lineOf(offset);
				}
				offset -= child.length + 1;
				line += child.lines;
			}
			throw new IndexOutOfBoundsException();
		}

		@Override
		public int maxLineLength() {
			return maxLine;
		}

		@Override
		public Text replace(int from, int to, Text text) {
			if (text.lines < lines) {
				for (int i = 0, pos = 0; i < children.length; ++i) {
					Text child = children[i];
					int end = pos + child.length;
					// Fast path: the change stays inside one child and
					// leaves its size in the accepted range.
					if (from >= pos && to <= end) {
						Text updated = child.replace(from - pos, to - pos, text);
						int totalLines = lines - child.lines + updated.lines;
						if (updated.lines < (totalLines >> (BRANCH_SHIFT - 1)) &&
								updated.lines > (totalLines >> (BRANCH_SHIFT + 1))) {
							Text[] copy = children.clone();
							copy[i] = updated;
							return new Node(copy,
									length - (to - from) + text.length);
						}
						return super.replace(pos, end, updated);
					}
					pos = end + 1;
				}
			}
			return super.replace(from, to, text);
		}

		@Override
		void decompose(int from, int to, List<Text> target, int open) {
			for (int i = 0, pos = 0; pos <= to && i < children.length; ++i) {
				Text child = children[i];
				int end = pos + child.length;
				if (from <= end && to >= pos) {
					int childOpen = open & ((pos <= from ? OPEN_FROM : 0) |
							(end >= to ? OPEN_TO : 0));
					if (pos >= from && end <= to && childOpen == 0) {
						target.add(child);
					} else {
						child.decompose(from - pos, to - pos, target, childOpen);
					}
				}
				pos = end + 1;
			}
		}

		@Override
		void appendTo(StringBuilder sb, int from, int to) {
			for (int i = 0, pos = 0; i < children.length && pos <= to; ++i) {
				Text child = children[i];
				int end = pos + child.length;
				if (from <= end && to >= pos) {
					child.appendTo(sb, Math.max(0, from - pos), to - pos);
				}
				if (end >= from && end < to && i < children.length - 1) {
					sb.append('\n');
				}
				pos = end + 1;
			}
		}

		@Override
		void flatten(List<String> target) {
			for (Text child : children) {
				child.flatten(target);
			}
		}
	}
}
