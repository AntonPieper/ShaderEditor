package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LineGeometryTest {
	@Test
	public void findsTheLineAtAPlace() {
		LineGeometry lines = lines(20);
		assertEquals(0, lines.lineAt(-50, 100));
		assertEquals(0, lines.lineAt(19.9f, 100));
		assertEquals(1, lines.lineAt(20, 100));
		assertEquals(49_999, lines.lineAt(20 * 49_999 + 5, 50_000));
		assertEquals(99, lines.lineAt(1e9f, 100));
		assertEquals(2000, lines.height(100), 0);
	}

	@Test
	public void aRowMovesTheLinesAfterItsLineDown() {
		LineGeometry lines = lines(20);
		lines.setRow(3, 50);
		assertEquals(60, lines.top(3), 0);
		assertEquals(80, lines.rowTop(), 0);
		assertEquals(130, lines.top(4), 0);
		assertEquals(20 * 9 + 50, lines.top(9), 0);
		assertEquals(20 * 10 + 50, lines.height(10), 0);
		assertEquals(20 * 3, lines.height(3), 0);
	}

	@Test
	public void theRowBelongsToItsLine() {
		LineGeometry lines = lines(20);
		lines.setRow(3, 50);
		assertEquals(3, lines.lineAt(79.9f, 10));
		assertEquals(3, lines.lineAt(80, 10));
		assertEquals(3, lines.lineAt(129.9f, 10));
		assertEquals(4, lines.lineAt(130, 10));
		assertEquals(9, lines.lineAt(1e9f, 10));
		for (int n = 0; n < 10; ++n) {
			assertEquals(n, lines.lineAt(lines.top(n), 10));
			assertEquals(n, lines.lineAt(lines.top(n) + 19.9f, 10));
		}
	}

	@Test
	public void noRowForLineMinusOne() {
		LineGeometry lines = lines(20);
		lines.setRow(3, 50);
		lines.setRow(-1, 50);
		assertEquals(0, lines.rowHeight());
		assertEquals(80, lines.top(4), 0);
		assertEquals(4, lines.lineAt(80, 10));
	}

	private static LineGeometry lines(int lineHeight) {
		LineGeometry lines = new LineGeometry();
		lines.setLineHeight(lineHeight);
		return lines;
	}
}
