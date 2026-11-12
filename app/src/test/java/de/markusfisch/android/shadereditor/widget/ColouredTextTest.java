package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.text.Spanned;

import org.junit.Test;

public class ColouredTextTest {
	private static final int FLAGS = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;

	@Test
	public void keepsTheText() {
		ColouredText t = new ColouredText("float a;", 0);
		assertEquals(8, t.length());
		assertEquals('a', t.charAt(6));
		assertEquals("float a;", t.toString());
		assertEquals("oat", t.subSequence(2, 5).toString());
	}

	@Test
	public void returnsSpansInTheOrderTheyWereSet() {
		ColouredText t = new ColouredText("float a = 1.0;", 1);
		Object a = new Object();
		Object b = new Object();
		Object c = new Object();
		t.setSpan(a, 0, 5, FLAGS);
		t.setSpan(b, 6, 7, FLAGS);
		t.setSpan(c, 10, 13, FLAGS);
		assertArrayEquals(new Object[]{a, b, c},
				t.getSpans(0, t.length(), Object.class));
		assertEquals(6, t.getSpanStart(b));
		assertEquals(7, t.getSpanEnd(b));
		assertEquals(FLAGS, t.getSpanFlags(b));
	}

	@Test
	public void filtersByKindAndRange() {
		ColouredText t = new ColouredText("0123456789", 0);
		Object o = new Object();
		String s = "a string span";
		t.setSpan(o, 2, 4, FLAGS);
		t.setSpan(s, 4, 6, FLAGS);
		assertArrayEquals(new String[]{s}, t.getSpans(0, 10, String.class));
		// Touching a non-empty span from outside is no overlap.
		assertArrayEquals(new Object[]{o}, t.getSpans(0, 4, Object.class));
		assertEquals(0, t.getSpans(6, 8, Object.class).length);
		// An empty range at a boundary finds both neighbours.
		assertArrayEquals(new Object[]{o, s}, t.getSpans(4, 4, Object.class));
	}

	@Test
	public void setSpanAgainMovesIt() {
		ColouredText t = new ColouredText("0123456789", 0);
		Object o = new Object();
		t.setSpan(o, 2, 4, FLAGS);
		t.setSpan(o, 5, 9, FLAGS);
		assertEquals(1, t.getSpans(0, 10, Object.class).length);
		assertEquals(5, t.getSpanStart(o));
		assertEquals(9, t.getSpanEnd(o));
	}

	@Test
	public void removeSpanKeepsTheOthers() {
		ColouredText t = new ColouredText("0123456789", 0);
		Object a = new Object();
		Object b = new Object();
		Object c = new Object();
		t.setSpan(a, 0, 1, FLAGS);
		t.setSpan(b, 1, 2, FLAGS);
		t.setSpan(c, 2, 3, FLAGS);
		t.removeSpan(b);
		t.removeSpan(new Object());
		assertArrayEquals(new Object[]{a, c}, t.getSpans(0, 10, Object.class));
		assertEquals(-1, t.getSpanStart(b));
		assertEquals(2, t.getSpanStart(c));
	}

	@Test
	public void findsTheNextTransition() {
		ColouredText t = new ColouredText("0123456789", 0);
		t.setSpan(new Object(), 2, 4, FLAGS);
		t.setSpan("s", 6, 8, FLAGS);
		assertEquals(2, t.nextSpanTransition(0, 10, Object.class));
		assertEquals(4, t.nextSpanTransition(2, 10, Object.class));
		assertEquals(6, t.nextSpanTransition(4, 10, null));
		assertEquals(6, t.nextSpanTransition(0, 10, String.class));
		assertEquals(5, t.nextSpanTransition(4, 5, Object.class));
	}

	@Test(expected = IndexOutOfBoundsException.class)
	public void refusesASpanOutsideTheText() {
		new ColouredText("abc", 0).setSpan(new Object(), 1, 4, FLAGS);
	}

	@Test
	public void growsPastItsCapacity() {
		ColouredText t = new ColouredText("x".repeat(1000), 2);
		for (int i = 0; i < 1000; ++i) {
			t.setSpan(Integer.valueOf(i), i, i + 1, FLAGS);
		}
		Object[] all = t.getSpans(0, 1000, Object.class);
		assertEquals(1000, all.length);
		assertEquals(999, t.getSpanStart(all[999]));
	}
}
