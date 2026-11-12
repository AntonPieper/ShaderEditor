package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class LineWidthsTest {
	/** Measures a line as its length and remembers what it measured. */
	private static final class Text implements LineWidths.Measure {
		final StringBuilder text = new StringBuilder();
		final List<Integer> measured = new ArrayList<>();

		Text(String s) {
			text.append(s);
		}

		String[] lines() {
			return text.toString().split("\n", -1);
		}

		@Override
		public float lineWidth(int line) {
			measured.add(line);
			return lines()[line].length();
		}

		float widest() {
			float max = 0;
			for (String line : lines()) {
				max = Math.max(max, line.length());
			}
			return max;
		}

		/** Replaces like an Editable and tells widths about it. */
		void replace(LineWidths widths, int start, int end, String with) {
			int firstLine = LineWidths.breaks(text, 0, start);
			int removed = LineWidths.breaks(text, start, end);
			text.replace(start, end, with);
			assertTrue(widths.replace(firstLine, removed,
					LineWidths.breaks(with, 0, with.length())));
		}
	}

	@Test
	public void measuresEveryLineOnceAfterReset() {
		Text t = new Text("ab\nabcd\n\na");
		LineWidths widths = new LineWidths();
		widths.reset(4);
		assertEquals(4f, widths.max(t), 0);
		assertEquals(List.of(0, 1, 2, 3), t.measured);
		t.measured.clear();
		assertEquals(4f, widths.max(t), 0);
		assertEquals(List.of(), t.measured);
	}

	@Test
	public void anEditInsideALineMeasuresOnlyThatLine() {
		Text t = new Text("ab\nabcd\nabc");
		LineWidths widths = new LineWidths();
		widths.reset(3);
		widths.max(t);
		t.measured.clear();
		t.replace(widths, 1, 1, "xxxxx");
		assertEquals(7f, widths.max(t), 0);
		assertEquals(List.of(0), t.measured);
	}

	@Test
	public void deletingTheWidestLineShrinks() {
		Text t = new Text("ab\nabcdef\nabc");
		LineWidths widths = new LineWidths();
		widths.reset(3);
		assertEquals(6f, widths.max(t), 0);
		t.replace(widths, 2, 9, "");
		assertEquals(2, widths.lineCount());
		assertEquals(3f, widths.max(t), 0);
	}

	@Test
	public void insertingLinesShiftsTheOthers() {
		Text t = new Text("a\nbb\nccc");
		LineWidths widths = new LineWidths();
		widths.reset(3);
		widths.max(t);
		t.measured.clear();
		// Split line 1 into three lines.
		t.replace(widths, 3, 3, "\nxxxx\n");
		assertEquals(5, widths.lineCount());
		assertEquals(4f, widths.max(t), 0);
		assertEquals(List.of(1, 2, 3), t.measured);
	}

	@Test
	public void anEditThatDoesNotFitIsRefused() {
		LineWidths widths = new LineWidths();
		widths.reset(2);
		assertFalse(widths.replace(1, 1, 0));
		assertFalse(widths.replace(-1, 0, 0));
		assertEquals(2, widths.lineCount());
	}

	@Test
	public void invalidateMeasuresAgain() {
		Text t = new Text("a\nbb\nccc\ndddd");
		LineWidths widths = new LineWidths();
		widths.reset(4);
		widths.max(t);
		t.measured.clear();
		widths.invalidate(-5, 1);
		widths.invalidate(3, 10);
		widths.max(t);
		assertEquals(List.of(0, 1, 3), t.measured);
	}

	@Test
	public void noLinesAreZeroWide() {
		LineWidths widths = new LineWidths();
		widths.reset(0);
		assertEquals(0f, widths.max(line -> 1), 0);
	}

	@Test
	public void countsBreaks() {
		assertEquals(0, LineWidths.breaks("abc", 0, 3));
		assertEquals(2, LineWidths.breaks("\na\n", 0, 3));
		assertEquals(1, LineWidths.breaks("\na\n", 1, 10));
		assertEquals(0, LineWidths.breaks("\n", -1, 0));
	}

	@Test
	public void randomEditsAlwaysGiveTheWidestLine() {
		Random random = new Random(42);
		Text t = new Text("void main() {\n\tgl_FragColor = vec4(1.0);\n}\n");
		LineWidths widths = new LineWidths();
		widths.reset(t.lines().length);
		String[] inserts = {"", "x", "\n", "abc\ndef", "\n\n", "long line here"};
		for (int i = 0; i < 2000; ++i) {
			int length = t.text.length();
			int start = random.nextInt(length + 1);
			int end = Math.min(length, start + random.nextInt(8));
			t.replace(widths, start, end,
					inserts[random.nextInt(inserts.length)]);
			if (random.nextInt(4) == 0) {
				assertEquals(t.lines().length, widths.lineCount());
				assertEquals(t.widest(), widths.max(t), 0);
			}
		}
	}
}
