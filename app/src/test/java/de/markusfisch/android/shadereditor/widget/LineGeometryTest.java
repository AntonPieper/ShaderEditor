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

	@Test
	public void keepsALineInPlaceWhenTheRowChanges() {
		LineGeometry lines = lines(20);
		// Opens above the anchor: everything above moves up.
		lines.setRow(3, 50);
		assertEquals(150, lines.keep(100, 9, -1, 0));
		// Grows.
		lines.setRow(3, 70);
		assertEquals(120, lines.keep(100, 9, 3, 50));
		// Moves below the anchor, then closes.
		lines.setRow(12, 70);
		assertEquals(30, lines.keep(100, 9, 3, 70));
		lines.setRow(-1, 0);
		assertEquals(100, lines.keep(100, 9, 12, 70));
		// The anchor's own row does not move it.
		lines.setRow(9, 50);
		assertEquals(100, lines.keep(100, 9, -1, 0));
		for (int scrollY = 0; scrollY < 200; scrollY += 37) {
			LineGeometry before = lines(20);
			before.setRow(2, 40);
			float onScreen = before.top(9) - scrollY;
			LineGeometry after = lines(20);
			after.setRow(5, 90);
			assertEquals(onScreen, after.top(9) - after.keep(scrollY, 9, 2,
					40), 0);
		}
	}

	@Test
	public void revealsALineWithItsRowByTheLeastScroll() {
		LineGeometry lines = lines(20);
		// 10 lines visible from 0 to 200; the row under line 9 is 50 tall.
		lines.setRow(9, 50);
		// Line and row fit: nothing moves.
		assertEquals(0, lines.reveal(0, 5, 0, 300));
		// The row hangs below the bottom: up by exactly the part cut off.
		assertEquals(50, lines.reveal(0, 9, 0, 200));
		// Without a row the line alone decides.
		lines.setRow(-1, 0);
		assertEquals(0, lines.reveal(0, 9, 0, 200));
		assertEquals(20, lines.reveal(0, 10, 0, 200));
		// Above the top: down to the line's top.
		assertEquals(60, lines.reveal(100, 3, 0, 200));
		// A row taller than the space: the line stays at the top.
		lines.setRow(9, 500);
		assertEquals(180, lines.reveal(0, 9, 0, 200));
		// The row of another line does not count.
		lines.setRow(4, 50);
		assertEquals(0, lines.reveal(0, 3, 0, 200));
		// Insets: the band starts and ends inside the view.
		lines.setRow(9, 50);
		assertEquals(90, lines.reveal(0, 9, 8, 160));
	}

	private static LineGeometry lines(int lineHeight) {
		LineGeometry lines = new LineGeometry();
		lines.setLineHeight(lineHeight);
		return lines;
	}
}
