package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TextTest {
	@Test
	public void emptyText() {
		for (Text text : new Text[]{Text.EMPTY, Text.of("")}) {
			assertEquals(0, text.length());
			assertEquals(1, text.lineCount());
			assertEquals("", text.line(0));
			assertEquals(0, text.lineStart(0));
			assertEquals(0, text.lineOf(0));
			assertEquals("", text.toString());
			assertEquals("", text.substring(0, 0));
		}
	}

	@Test
	public void lastLineWithoutLineBreak() {
		Text text = Text.of("a\nbc");
		assertEquals(4, text.length());
		assertEquals(2, text.lineCount());
		assertEquals("a", text.line(0));
		assertEquals("bc", text.line(1));
		assertEquals(2, text.lineStart(1));
		assertEquals(0, text.lineOf(1));
		assertEquals(1, text.lineOf(2));
		assertEquals(1, text.lineOf(4));
	}

	@Test
	public void finalLineBreakStartsAnEmptyLine() {
		Text text = Text.of("a\n");
		assertEquals(2, text.lineCount());
		assertEquals("", text.line(1));
		assertEquals(2, text.lineStart(1));
		assertEquals(0, text.lineOf(1));
		assertEquals(1, text.lineOf(2));
	}

	@Test
	public void crLfAndLoneCrEndLines() {
		assertText("a\nb\nc\n\nd", Text.of("a\r\nb\rc\n\r\nd"));
		assertText("\n\n", Text.of("\r\r\n"));
		assertText("\n", Text.of("\r"));
		assertText("a\n", Text.of("a\r"));
	}

	@Test
	public void noBreakSpaceBecomesSpace() {
		assertText("a b  ", Text.of("a\u00a0b\u00a0\u00a0"));
	}

	@Test
	public void oneMegabyteLine() {
		String line = "abcdefgh".repeat(1 << 17);
		Text text = Text.of(line);
		assertEquals(1 << 20, text.length());
		assertEquals(1, text.lineCount());
		assertSame(line, text.line(0));
		assertEquals(0, text.lineStart(0));
		assertEquals(0, text.lineOf(0));
		assertEquals(0, text.lineOf(1 << 19));
		assertEquals(0, text.lineOf(1 << 20));
		assertEquals("habc", text.substring((1 << 19) - 1, (1 << 19) + 3));
	}

	@Test
	public void offsetsAndLinesAtEveryBoundary() {
		Random random = new Random(1);
		// Around the sizes of a leaf, a node of leaves, and a level more.
		for (int lines : new int[]{1, 2, 31, 32, 33, 64, 65, 1023, 1024,
				1025, 1057, 3000, 32769}) {
			String string = randomLines(random, lines);
			assertBoundaries(string, Text.of(string));
		}
	}

	@Test
	public void substrings() {
		Random random = new Random(2);
		String string = randomLines(random, 2000);
		Text text = Text.of(string);
		List<Integer> breaks = lineBreaks(string);
		for (int i = 0; i < 5000; ++i) {
			int a = pick(random, string, breaks);
			int b = pick(random, string, breaks);
			int from = Math.min(a, b);
			int to = Math.max(a, b);
			assertEquals(string.substring(from, to), text.substring(from, to));
		}
		assertEquals(string, text.toString());
	}

	@Test
	public void buildsAShallowTree() throws Exception {
		Text text = Text.of(randomLines(new Random(3), 500_000));
		assertEquals(500_000, text.lineCount());
		// 32 lines a leaf, 32 children a node: 15,625 leaves in 4 levels.
		assertEquals(4, depth(text));
	}

	@Test
	public void rejectsPositionsOutside() {
		Text text = Text.of("ab\ncd");
		assertThrows(IndexOutOfBoundsException.class, () -> text.line(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> text.line(2));
		assertThrows(IndexOutOfBoundsException.class, () -> text.lineStart(2));
		assertThrows(IndexOutOfBoundsException.class, () -> text.lineOf(-1));
		assertThrows(IndexOutOfBoundsException.class, () -> text.lineOf(6));
		assertThrows(IndexOutOfBoundsException.class,
				() -> text.substring(4, 6));
		assertThrows(IndexOutOfBoundsException.class,
				() -> text.substring(2, 1));
	}

	static void assertText(String expected, Text text) {
		assertEquals(expected, text.toString());
		assertEquals(expected.length(), text.length());
		assertEquals(expected.split("\n", -1).length, text.lineCount());
	}

	// Checks lineOf for every offset and lineStart and line for every line.
	static void assertBoundaries(String expected, Text text) {
		String[] lines = expected.split("\n", -1);
		assertEquals(lines.length, text.lineCount());
		int start = 0;
		for (int n = 0; n < lines.length; ++n) {
			assertEquals(lines[n], text.line(n));
			assertEquals(start, text.lineStart(n));
			int end = start + lines[n].length();
			for (int offset = start; offset <= end; ++offset) {
				assertEquals(n, text.lineOf(offset));
			}
			start = end + 1;
		}
	}

	static String randomLines(Random random, int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; ++i) {
			if (i > 0) {
				sb.append('\n');
			}
			int length = random.nextInt(8) == 0 ? 0 : random.nextInt(60);
			for (int j = 0; j < length; ++j) {
				sb.append((char) ('a' + random.nextInt(26)));
			}
		}
		return sb.toString();
	}

	static List<Integer> lineBreaks(String string) {
		List<Integer> breaks = new ArrayList<>();
		for (int i = string.indexOf('\n'); i > -1;
				i = string.indexOf('\n', i + 1)) {
			breaks.add(i);
		}
		return breaks;
	}

	// Picks a line break, the offset behind one, an end, or any offset.
	static int pick(Random random, String string, List<Integer> breaks) {
		switch (random.nextInt(4)) {
			case 0:
				return breaks.get(random.nextInt(breaks.size()));
			case 1:
				return breaks.get(random.nextInt(breaks.size())) + 1;
			case 2:
				return random.nextBoolean() ? 0 : string.length();
			default:
				return random.nextInt(string.length() + 1);
		}
	}

	static Text[] children(Text text) throws Exception {
		Class<?> type = text.getClass();
		if (!type.getSimpleName().equals("Node")) {
			return new Text[0];
		}
		Field field = type.getDeclaredField("children");
		field.setAccessible(true);
		return (Text[]) field.get(text);
	}

	static int depth(Text text) throws Exception {
		int depth = 0;
		for (Text child : children(text)) {
			depth = Math.max(depth, depth(child));
		}
		return depth + 1;
	}
}
