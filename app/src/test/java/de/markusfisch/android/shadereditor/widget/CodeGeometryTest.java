package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CodeGeometryTest {
	@Test
	public void findsTheLineAtAPlace() {
		// Lines of 20 pixels from 8 on.
		assertEquals(0, CodeGeometry.lineAt(-50, 8, 20, 100));
		assertEquals(0, CodeGeometry.lineAt(27.9f, 8, 20, 100));
		assertEquals(1, CodeGeometry.lineAt(28, 8, 20, 100));
		assertEquals(49_999, CodeGeometry.lineAt(8 + 20 * 49_999 + 5, 8, 20,
				50_000));
		assertEquals(99, CodeGeometry.lineAt(1e9f, 8, 20, 100));
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
