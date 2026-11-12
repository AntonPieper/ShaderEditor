package de.markusfisch.android.shadereditor.editor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Random;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Tokens;

/**
 * THROWAWAY PROTOTYPE (proto/editor-core). A plain main(), not a test:
 * checks the document model against a StringBuilder and measures it.
 * Run: java -cp out de.markusfisch.android.shadereditor.editor.EditorBench
 * path/to/horizon-5000.glsl
 */
public final class EditorBench {
	private static String base;

	public static void main(String[] args) throws Exception {
		base = new String(Files.readAllBytes(Paths.get(args[0])),
				StandardCharsets.UTF_8);
		if (!base.endsWith("\n")) {
			base += "\n";
		}
		keepPaste = Text.of(base);
		check();
		checkLexer();
		for (int copies : new int[]{1, 10, 100}) {
			bench(copies);
		}
		longLine();
	}

	private static String repeat(int copies) {
		StringBuilder sb = new StringBuilder(base.length() * copies);
		for (int i = 0; i < copies; ++i) {
			sb.append(base);
		}
		return sb.toString();
	}

	// ---- correctness ----

	private static void check() {
		Random random = new Random(1);
		String alphabet = "ab \n\n\t{};/*";
		for (int round = 0; round < 40; ++round) {
			String start = base.substring(0, random.nextInt(4000));
			StringBuilder ref = new StringBuilder(start);
			Document doc = new Document(Text.of(start));
			LineStates states = new LineStates(doc.text());
			doc.addListener((d, change, before) -> {
				int first = before.lineOf(change.start);
				states.update(d.text(), first, change.removed.lineCount() - 1,
						change.inserted.lineCount() - 1, Integer.MAX_VALUE);
			});
			for (int i = 0; i < 400; ++i) {
				int from = random.nextInt(ref.length() + 1);
				int to = Math.min(ref.length(),
						from + (random.nextInt(4) == 0 ? random.nextInt(300) : 0));
				int n = random.nextInt(5) == 0 ? random.nextInt(200) : random.nextInt(3);
				StringBuilder ins = new StringBuilder();
				for (int k = 0; k < n; ++k) {
					ins.append(alphabet.charAt(random.nextInt(alphabet.length())));
				}
				ref.replace(from, to, ins.toString());
				doc.replace(from, to, Text.of(ins), false);
				if (random.nextInt(10) == 0 && doc.canUndo()) {
					String now = ref.toString();
					doc.undo();
					doc.redo();
					require(doc.text().toString().equals(now), "undo/redo");
				}
				Text text = doc.text();
				require(text.length() == ref.length(), "length");
				if (i % 20 == 0) {
					String s = ref.toString();
					require(text.toString().equals(s), "content");
					String[] lines = s.split("\n", -1);
					require(lines.length == text.lineCount(), "line count");
					int line = random.nextInt(lines.length);
					require(text.line(line).equals(lines[line]), "line");
					int offset = random.nextInt(s.length() + 1);
					int lineOf = text.lineOf(offset);
					int lineStart = text.lineStart(lineOf);
					require(lineStart <= offset &&
							offset <= lineStart + text.line(lineOf).length(),
							"lineOf/lineStart");
					int a = random.nextInt(s.length() + 1);
					int b = a + random.nextInt(s.length() - a + 1);
					require(text.substring(a, b).equals(s.substring(a, b)),
							"substring");
					require(text.slice(a, b).toString().equals(s.substring(a, b)),
							"slice");
					LineStates fresh = new LineStates(text);
					for (int l = 0; l < text.lineCount(); ++l) {
						require(fresh.startOf(l) == states.startOf(l),
								"incremental line state at " + l);
					}
				}
			}
			// Undo everything.
			while (doc.canUndo()) {
				doc.undo();
			}
			require(doc.text().toString().equals(start), "undo to start");
		}
		System.out.println("check: Text, Document, undo and incremental line " +
				"states agree with a StringBuilder over 16,000 random edits");
	}

	// Per-line tokens against the tokens of the whole text.
	private static void checkLexer() {
		String s = base;
		Tokens whole = GlslLexer.lex(s);
		byte[] expected = new byte[s.length()];
		Arrays.fill(expected, (byte) -1);
		for (int i = 0; i < whole.size(); ++i) {
			for (int c = whole.start(i); c < whole.end(i); ++c) {
				expected[c] = (byte) whole.style(i).ordinal();
			}
		}
		Text text = Text.of(s);
		LineTokens tokens = new LineTokens();
		int state = GlslLexer.LINE_START;
		int different = 0;
		int differentLines = 0;
		for (int line = 0, lines = text.lineCount(); line < lines; ++line) {
			String l = text.line(line);
			int start = text.lineStart(line);
			state = GlslLexer.lexLine(l, line < lines - 1, state, tokens);
			byte[] actual = new byte[l.length()];
			Arrays.fill(actual, (byte) -1);
			for (int i = 0; i < tokens.size; ++i) {
				for (int c = tokens.starts[i]; c < tokens.ends[i]; ++c) {
					actual[c] = (byte) tokens.style(i).ordinal();
				}
			}
			boolean same = true;
			for (int c = 0; c < actual.length; ++c) {
				if (actual[c] != expected[start + c]) {
					++different;
					same = false;
				}
			}
			if (!same && ++differentLines <= 3) {
				System.out.println("  differs, line " + (line + 1) + ": " + l);
			}
		}
		System.out.println("check: per-line tokens differ from whole-text " +
				"tokens in " + different + " of " + s.length() +
				" characters, on " + differentLines + " of " +
				text.lineCount() + " lines");
	}

	private static void require(boolean ok, String what) {
		if (!ok) {
			throw new AssertionError(what);
		}
	}

	// ---- measurement ----

	private interface Body {
		void run(int i);
	}

	// Median nanoseconds of one call, after a warm-up.
	private static double time(int warmup, int runs, Body body) {
		for (int i = 0; i < warmup; ++i) {
			body.run(i);
		}
		long[] t = new long[runs];
		for (int i = 0; i < runs; ++i) {
			long start = System.nanoTime();
			body.run(i);
			t[i] = System.nanoTime() - start;
		}
		Arrays.sort(t);
		return t[runs / 2];
	}

	private static String ms(double ns) {
		return String.format("%.3f ms", ns / 1e6);
	}

	private static String us(double ns) {
		return String.format("%.2f us", ns / 1e3);
	}

	private static long usedMemory() {
		for (int i = 0; i < 4; ++i) {
			System.gc();
		}
		Runtime rt = Runtime.getRuntime();
		return rt.totalMemory() - rt.freeMemory();
	}

	private static void bench(int copies) {
		final String s = repeat(copies);
		long memBefore = usedMemory();
		Text[] keep = new Text[1];
		keep[0] = Text.of(s);
		long memText = usedMemory() - memBefore;
		final Text text = keep[0];
		final int lines = text.lineCount();
		System.out.println();
		System.out.println("=== " + lines + " lines, " + s.length() +
				" chars ===");
		System.out.println("memory of Text: " + memText / 1024 + " KB (" +
				String.format("%.2f", memText / (double) s.length()) +
				" bytes per char; the String alone is " + s.length() / 1024 +
				" KB as Latin-1)");
		System.out.println("load  Text.of(String):           " +
				ms(time(5, 15, i -> Text.of(s))));
		System.out.println("snapshot Text.toString():        " +
				ms(time(5, 15, i -> text.toString())));
		final Text single = Text.of("x");
		final Text newline = Text.of("\n");
		final int middle = text.lineStart(lines / 2) +
				text.line(lines / 2).length();

		// Typing at one place: every call works on the result of the
		// last; every 40th key is Enter, so lines keep a normal length.
		final Text[] t = {text};
		final int[] at = {middle};
		System.out.println("type at the caret (1 in 40 is Enter): " +
				us(time(20000, 20000, i -> {
					t[0] = t[0].replace(at[0], at[0],
							i % 40 == 39 ? newline : single);
					++at[0];
				})));
		System.out.println("backspace at the caret:           " +
				us(time(10000, 20000, i -> {
					t[0] = t[0].replace(at[0] - 1, at[0], Text.EMPTY);
					--at[0];
				})));
		t[0] = text;
		at[0] = middle;
		final Random random = new Random(7);
		final int[] where = new int[40000];
		for (int i = 0; i < where.length; ++i) {
			where[i] = random.nextInt(text.length() - 100);
		}
		System.out.println("type 1 char at a random place:    " +
				us(time(20000, 20000, i -> {
					t[0] = t[0].replace(where[i], where[i], single);
				})));
		System.out.println("offset to line (lineOf):          " +
				us(time(20000, 20000, i -> text.lineOf(where[i]))));
		System.out.println("line to offset (lineStart):       " +
				us(time(20000, 20000, i -> text.lineStart(where[i] % lines))));
		System.out.println("read one line (line):             " +
				us(time(20000, 20000, i -> text.line(where[i] % lines))));

		// Paste: the pasted string becomes a Text first (cost by its size),
		// then one replace.
		final String pasted = base;
		System.out.println("paste 5,002 lines (235 KB): build " +
				ms(time(5, 15, i -> Text.of(pasted))) + ", insert " +
				us(time(200, 400, i -> text.replace(middle, middle,
						keepPaste))));
		final String big = repeat(4);
		final Text[] bigText = new Text[1];
		System.out.println("paste 20,008 lines (940 KB): build " +
				ms(time(5, 15, i -> bigText[0] = Text.of(big))) + ", insert " +
				us(time(200, 400, i -> text.replace(middle, middle,
						bigText[0]))));
		System.out.println("select all + delete:              " +
				us(time(200, 400, i -> text.replace(0, text.length(),
						Text.EMPTY))));
		System.out.println("cut the middle half (slice kept): " +
				us(time(200, 400, i -> {
					text.slice(text.length() / 4, text.length() * 3 / 4);
					text.replace(text.length() / 4, text.length() * 3 / 4,
							Text.EMPTY);
				})));

		// Undo and redo of a large change through the document.
		final Document doc = new Document(text);
		System.out.println("paste 5,002 lines, undo, redo:    " +
				us(time(200, 400, i -> {
					doc.replace(middle, middle, keepPaste, false);
					doc.undo();
					doc.redo();
					doc.undo();
				}) / 4) + " per step");

		// The same edits on a gap buffer, to compare.
		final GapBuffer gap = new GapBuffer(s);
		final int[] g = {middle};
		System.out.println("gap buffer: type 1 char, caret in the middle: " +
				us(time(20000, 20000, i -> gap.insert(g[0]++, 'x'))));
		System.out.println("gap buffer: type 1 char at a random place:    " +
				us(time(2000, 2000, i -> gap.insert(where[i], 'x'))));
		System.out.println("gap buffer: copy for another thread:          " +
				ms(time(5, 15, i -> gap.snapshot())));

		// Tokens.
		System.out.println("lex whole text (GlslLexer.lex):   " +
				ms(time(30, 15, i -> GlslLexer.lex(s))));
		final Tokens tokens = GlslLexer.lex(s);
		System.out.println("outline of whole text:            " +
				ms(time(5, 15, i -> Outline.of(tokens, s))));
		System.out.println("lex every line once (LineStates): " +
				ms(time(20, 9, i -> new LineStates(text))));
		final LineStates states = new LineStates(text);
		final Text[] u = {text};
		final int[] p = {middle};
		final int line = lines / 2;
		final int[] l = {line};
		System.out.println("type + re-lex (1 in 40 is Enter): " +
				us(time(20000, 20000, i -> {
					boolean enter = i % 40 == 39;
					u[0] = u[0].replace(p[0], p[0], enter ? newline : single);
					++p[0];
					states.update(u[0], l[0], 0, enter ? 1 : 0, 200);
					if (enter) {
						++l[0];
					}
				})));
		// A block comment opened on line 10: with a budget, then all of it.
		final Text opened = text.replace(text.lineStart(10), text.lineStart(10),
				Text.of("/*"));
		final int[] counted = new int[1];
		System.out.println("after typing /* on line 11: 200 lines now " +
				us(time(50, 200, i -> {
					LineStates fresh = copyOf(states0(text));
					counted[0] = fresh.update(opened, 10, 0, 0, 200);
				}) - time(50, 200, i -> copyOf(states0(text)))) +
				" (" + counted[0] + " lines)");
		final LineStates dirty = states0(text);
		dirty.update(opened, 10, 0, 0, 200);
		System.out.println("  then the rest, off the main thread:      " +
				ms(time(0, 1, i -> counted[0] = dirty.advance(opened,
						Integer.MAX_VALUE))) + " (" + counted[0] +
				" lines, cold, one run)");
	}

	private static Text keepPaste;
	private static LineStates cached;
	private static Text cachedFor;

	private static LineStates states0(Text text) {
		if (cachedFor != text) {
			cachedFor = text;
			cached = new LineStates(text);
		}
		return cached;
	}

	private static LineStates copyOf(LineStates states) {
		return states.copy();
	}

	private static void longLine() {
		System.out.println();
		System.out.println("=== one long line ===");
		for (int length : new int[]{1000, 10000, 100000, 1000000}) {
			char[] chars = new char[length];
			Arrays.fill(chars, 'a');
			final Text text = Text.of(base + new String(chars) + "\n" + base);
			final int at = base.length() + length / 2;
			final Text single = Text.of("x");
			final Text[] t = {text};
			System.out.println("type 1 char in a line of " + length +
					" chars: " + us(time(2000, 2000,
					i -> t[0] = t[0].replace(at, at, single))));
		}
	}

	/** Characters in a gap buffer and line starts in an int array. */
	private static final class GapBuffer {
		private char[] chars;
		private int gapStart;
		private int gapEnd;
		private int[] lineStarts;
		private int lineCount;

		private GapBuffer(String s) {
			chars = new char[s.length() + 65536];
			s.getChars(0, s.length(), chars, 0);
			gapStart = s.length();
			gapEnd = chars.length;
			lineStarts = new int[1 << 20];
			lineStarts[lineCount++] = 0;
			for (int i = 0; i < s.length(); ++i) {
				if (s.charAt(i) == '\n') {
					lineStarts[lineCount++] = i + 1;
				}
			}
		}

		private void insert(int at, char c) {
			if (at < gapStart) {
				int n = gapStart - at;
				System.arraycopy(chars, at, chars, gapEnd - n, n);
				gapStart -= n;
				gapEnd -= n;
			} else if (at > gapStart) {
				int n = at - gapStart;
				System.arraycopy(chars, gapEnd, chars, gapStart, n);
				gapStart += n;
				gapEnd += n;
			}
			if (gapStart == gapEnd) {
				char[] grown = new char[chars.length + 65536];
				System.arraycopy(chars, 0, grown, 0, gapStart);
				int tail = chars.length - gapEnd;
				System.arraycopy(chars, gapEnd, grown, grown.length - tail,
						tail);
				gapEnd = grown.length - tail;
				chars = grown;
			}
			chars[gapStart++] = c;
			// Every line start behind the insertion moves by one.
			int line = Arrays.binarySearch(lineStarts, 0, lineCount, at + 1);
			if (line < 0) {
				line = -line - 1;
			}
			for (int i = line; i < lineCount; ++i) {
				++lineStarts[i];
			}
		}

		private String snapshot() {
			StringBuilder sb = new StringBuilder(chars.length);
			sb.append(chars, 0, gapStart);
			sb.append(chars, gapEnd, chars.length - gapEnd);
			return sb.toString();
		}
	}
}
