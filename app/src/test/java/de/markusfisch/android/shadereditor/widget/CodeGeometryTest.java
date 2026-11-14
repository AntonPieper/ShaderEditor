package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CodeGeometryTest {
	@Test
	public void recordsTheNumbersOfWholeBlocksOfLines() {
		int block = CodeGeometry.GUTTER_BLOCK;
		assertEquals(0, CodeGeometry.numbersFrom(block - 1));
		assertEquals(block, CodeGeometry.numbersFrom(block));
		assertEquals(2 * block - 1, CodeGeometry.numbersTo(block, 1000));
		assertEquals(block + 4, CodeGeometry.numbersTo(block, block + 5));
	}

	@Test
	public void widensTheGutterWithTheDigitsOfTheLineCount() {
		assertEquals(1, CodeGeometry.digits(0));
		assertEquals(1, CodeGeometry.digits(9));
		assertEquals(2, CodeGeometry.digits(10));
		assertEquals(6, CodeGeometry.digits(500_000));
		assertEquals(3 * 7f + 8, CodeGeometry.gutterWidth(999, 7, 8), 0);
		assertEquals(4 * 7f + 8, CodeGeometry.gutterWidth(1000, 7, 8), 0);
	}
}
