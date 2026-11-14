package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class MessageRowTest {
	// One unit per character.
	private static final MessageRow.Style MONO = new MessageRow.Style() {
		@Override
		public float width(String text) {
			return text.length();
		}

		@Override
		public int fit(String text, int start, float width) {
			return Math.min(text.length() - start, (int) width);
		}

		@Override
		public String ellipsis() {
			return "~";
		}

		@Override
		public String more(int count) {
			return "+" + count;
		}
	};

	@Test
	public void opensTheCaretsLineFirst() {
		int[] failing = {3, 9, 20};
		assertEquals(20, MessageRow.choose(failing, 9, 20, 0, 10));
		assertEquals(9, MessageRow.choose(failing, 9, 4, 0, 10));
		assertEquals(3, MessageRow.choose(failing, -1, 4, 0, 10));
		assertEquals(3, MessageRow.choose(failing, 3, 4, 5, 10));
		assertEquals(9, MessageRow.choose(new int[]{9, 20}, 3, 4, 5, 10));
		assertEquals(-1, MessageRow.choose(failing, -1, 4, 10, 19));
		assertEquals(-1, MessageRow.choose(new int[0], 3, 3, 0, 10));
	}

	@Test
	public void wrapsEachMessageFromANewLine() {
		assertArrayEquals(new String[]{"'x' : undeclared", "identifier",
						"syntax error"},
				MessageRow.lines(List.of("'x' : undeclared identifier",
						"syntax error"), 17, MONO));
		assertArrayEquals(new String[]{"abcdefghij", "klm"},
				MessageRow.lines(List.of("abcdefghijklm"), 10, MONO));
		assertArrayEquals(new String[0],
				MessageRow.lines(List.of(), 10, MONO));
	}

	@Test
	public void cutsAtThreeLinesAndCountsWhatIsLeftOut() {
		assertArrayEquals(new String[]{"one", "two", "three +2"},
				MessageRow.lines(List.of("one", "two", "three", "four",
						"five"), 10, MONO));
		assertArrayEquals(new String[]{"aaa bbb", "ccc ddd", "eee~"},
				MessageRow.lines(List.of("aaa bbb ccc ddd eee fff ggg"), 7,
						MONO));
		assertArrayEquals(new String[]{"aaa bbb", "ccc ddd", "eee fff +1"},
				MessageRow.lines(List.of("aaa bbb ccc ddd eee fff",
						"next"), 10, MONO));
		assertArrayEquals(new String[]{"aaa bbb", "ccc ddd", "eee~ +1"},
				MessageRow.lines(List.of("aaa bbb ccc ddd eee fff",
						"next"), 9, MONO));
		assertArrayEquals(new String[]{"one", "two", "three +1"},
				MessageRow.lines(List.of(" one ", "two", "three ", "four"),
						10, MONO));
		assertArrayEquals(new String[]{"a", "b", "c~ +1"},
				MessageRow.lines(List.of("a", "b", "c long", "d"), 5,
						MONO));
	}
}
