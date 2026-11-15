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
	public void labelShortensByRuleAndIsNeverCut() {
		List<String> labels = List.of(
				"Add uniform mat2 cameraOrientation",
				"Add uniform cameraOrientation",
				"Add uniform");
		assertEquals("Add uniform mat2 cameraOrientation",
				MessageRow.label(labels, 34, MONO));
		assertEquals("Add uniform cameraOrientation",
				MessageRow.label(labels, 33, MONO));
		assertEquals("Add uniform", MessageRow.label(labels, 28, MONO));
		// Too narrow for any: the shortest, whole.
		assertEquals("Add uniform", MessageRow.label(labels, 4, MONO));
		assertEquals("Add 2 uniforms", MessageRow.label(
				List.of("Add 2 uniforms"), 4, MONO));
	}

	@Test
	public void aNameIsCutInTheMiddleKeepingWhatIsAroundIt() {
		String label = "Use a_very_long_name.frag";
		int start = label.indexOf("a_very");
		int end = label.indexOf(".frag");
		assertEquals(label, MessageRow.cutMiddle(label, start, end, 25,
				MONO, "~"));
		assertEquals("Use a_ve~ame.frag", MessageRow.cutMiddle(label, start,
				end, 17, MONO, "~"));
		// No name to cut: as it is.
		assertEquals("Create", MessageRow.cutMiddle("Create", -1, -1, 2,
				MONO, "~"));
	}

	// Padding 1 and a gap of 2, by characters.
	private static final MessageRow.FixMetrics FIXES =
			new MessageRow.FixMetrics(1, 2, MONO, "~");

	@Test
	public void twoFixesSitSideBySideEndAligned() {
		List<MessageRow.Placed> placed = MessageRow.place(List.of(
				CodeView.Fix.of("Use a.frag"), CodeView.Fix.of("Create")),
				40, FIXES);
		assertEquals(2, placed.size());
		// The last ends at the end, the first 2 before it.
		assertEquals(new MessageRow.Placed("Create", 1, 0, 32, 40),
				placed.get(1));
		assertEquals(new MessageRow.Placed("Use a.frag", 0, 0, 18, 30),
				placed.get(0));
	}

	@Test
	public void twoFixesShortenThenTheSecondGoesUnderTheFirst() {
		CodeView.Fix uniform = CodeView.Fix.of("Add uniform float time",
				List.of("Add uniform float time", "Add uniform time",
						"Add uniform"));
		CodeView.Fix create = CodeView.Fix.of("Create");
		// Shortened to fit side by side: 13 + 2 + 8.
		List<MessageRow.Placed> placed = MessageRow.place(
				List.of(uniform, create), 23, FIXES);
		assertEquals("Add uniform", placed.get(0).label());
		assertEquals(0, placed.get(1).line());
		// Narrower still: one under the other, each at the end.
		placed = MessageRow.place(List.of(uniform, create), 14, FIXES);
		assertEquals(0, placed.get(0).line());
		assertEquals(1, placed.get(1).line());
		assertEquals(14, placed.get(1).end(), 0);
	}

	@Test
	public void aLongUseCutsTheNameNotTheExtension() {
		String label = "Use a_very_long_name.frag";
		List<MessageRow.Placed> placed = MessageRow.place(List.of(
				CodeView.Fix.cutting(label, 4, label.indexOf(".frag"))),
				19, FIXES);
		assertEquals("Use a_ve~ame.frag", placed.get(0).label());
	}

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
