package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LineNumberGutterTest {
	private static final float DIGIT = 7.5f;
	private static final float PADDING = 8f;
	private static final float SPACING = 16f;

	@Test
	public void digits() {
		assertEquals(1, LineNumberGutter.digits(-5));
		assertEquals(1, LineNumberGutter.digits(0));
		assertEquals(1, LineNumberGutter.digits(9));
		assertEquals(2, LineNumberGutter.digits(10));
		assertEquals(2, LineNumberGutter.digits(99));
		assertEquals(3, LineNumberGutter.digits(100));
		assertEquals(4, LineNumberGutter.digits(5002));
		assertEquals(9, LineNumberGutter.digits(999_999_999));
		assertEquals(10, LineNumberGutter.digits(1_000_000_000));
		assertEquals(10, LineNumberGutter.digits(Integer.MAX_VALUE));
	}

	@Test
	public void paragraphCount() {
		assertEquals(1, LineNumberGutter.paragraphCount(""));
		assertEquals(1, LineNumberGutter.paragraphCount("void main() {}"));
		assertEquals(2, LineNumberGutter.paragraphCount("a\n"));
		assertEquals(3, LineNumberGutter.paragraphCount("a\n\nb"));
	}

	@Test
	public void paddingIsTheViewsOwnWhenNumbersAreHidden() {
		assertEquals(24, LineNumberGutter.textPaddingLeft(
				false, 5000, DIGIT, PADDING, SPACING, 24));
	}

	@Test
	public void paddingMatchesTheFormerDrawCode() {
		for (int lines : new int[]{0, 1, 9, 10, 99, 100, 999, 1000, 5002}) {
			int digits = LineNumberGutter.digits(lines);
			int expected = (int) ((int) (DIGIT * digits + PADDING) + SPACING);
			assertEquals(expected, LineNumberGutter.textPaddingLeft(
					true, lines, DIGIT, PADDING, SPACING, 24));
		}
	}

	@Test
	public void paddingChangesOnlyWhenTheDigitCountChanges() {
		int at10 = LineNumberGutter.textPaddingLeft(
				true, 10, DIGIT, PADDING, SPACING, 0);
		int at99 = LineNumberGutter.textPaddingLeft(
				true, 99, DIGIT, PADDING, SPACING, 0);
		int at100 = LineNumberGutter.textPaddingLeft(
				true, 100, DIGIT, PADDING, SPACING, 0);
		assertEquals(at10, at99);
		assertTrue(at100 > at99);
	}

	@Test
	public void numbersAreRightAligned() {
		assertEquals(PADDING + 2 * DIGIT,
				LineNumberGutter.numberX(7, 120, DIGIT, PADDING), 0f);
		assertEquals(PADDING + DIGIT,
				LineNumberGutter.numberX(42, 120, DIGIT, PADDING), 0f);
		assertEquals(PADDING,
				LineNumberGutter.numberX(120, 120, DIGIT, PADDING), 0f);
	}

	@Test
	public void lineRangeCoversTheLinesInTheArea() {
		// Lines 20 px high.
		LineNumberGutter.LineForVertical layout = y -> y / 20;
		long range = LineNumberGutter.lineRange(5000, 1000, 1999, layout);
		assertEquals(50, LineNumberGutter.first(range));
		assertEquals(99, LineNumberGutter.last(range));
		// A partly visible line counts.
		range = LineNumberGutter.lineRange(5000, 1010, 2005, layout);
		assertEquals(50, LineNumberGutter.first(range));
		assertEquals(100, LineNumberGutter.last(range));
	}

	@Test
	public void lineRangeIsClampedToTheText() {
		LineNumberGutter.LineForVertical layout = y -> y / 20;
		long range = LineNumberGutter.lineRange(30, -500, 5000, layout);
		assertEquals(0, LineNumberGutter.first(range));
		assertEquals(29, LineNumberGutter.last(range));
	}

	@Test
	public void lineRangeIsEmptyWithoutLines() {
		long range = LineNumberGutter.lineRange(0, 0, 100, y -> 0);
		assertTrue(LineNumberGutter.first(range) > LineNumberGutter.last(range));
		range = LineNumberGutter.lineRange(10, 100, 0, y -> 0);
		assertTrue(LineNumberGutter.first(range) > LineNumberGutter.last(range));
	}

	@Test
	public void coversOnlyWhenTheVisibleAreaIsInside() {
		assertTrue(LineNumberGutter.covers(0, 3000, 1000, 2000));
		assertTrue(LineNumberGutter.covers(0, 3000, 0, 3000));
		assertFalse(LineNumberGutter.covers(0, 3000, 2500, 3500));
		assertFalse(LineNumberGutter.covers(1000, 3000, 500, 1500));
	}
}
