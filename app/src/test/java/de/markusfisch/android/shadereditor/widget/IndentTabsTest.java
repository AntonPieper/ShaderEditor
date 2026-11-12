package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class IndentTabsTest {
	@Test
	public void stopsAreMultiplesOfTheTabWidth() {
		int[] stops = IndentTabs.stops(15);
		assertEquals(IndentTabs.STOP_COUNT, stops.length);
		for (int i = 0; i < stops.length; ++i) {
			assertEquals(15 * (i + 1), stops[i]);
		}
	}

	@Test
	public void noStopsWithoutAWidth() {
		assertArrayEquals(new int[0], IndentTabs.stops(0));
		assertArrayEquals(new int[0], IndentTabs.stops(-3));
	}

	@Test
	public void leadingTabsNeedNoSpan() {
		assertEquals(List.of(), tabsWithoutStop("\t\tfloat a;\n\tb;\n", 32));
	}

	@Test
	public void tabsAfterOtherCharactersNeedASpan() {
		String text = "\tfloat a;\t// a\n  \tb;\n\t \tc;\na\tb\tc\n";
		List<Integer> expected = new ArrayList<>();
		expected.add(text.indexOf("\t// a"));
		expected.add(text.indexOf("\tb;"));
		expected.add(text.indexOf("\tc;"));
		int abc = text.indexOf("a\tb\tc");
		expected.add(abc + 1);
		expected.add(abc + 3);
		assertEquals(expected, tabsWithoutStop(text, 32));
	}

	@Test
	public void tabsDeeperThanTheLastStopNeedASpan() {
		assertEquals(List.of(2, 3), tabsWithoutStop("\t\t\t\tx", 2));
	}

	@Test
	public void withoutStopsEveryTabNeedsASpan() {
		assertEquals(List.of(0, 1), tabsWithoutStop("\t\tx", 0));
	}

	@Test
	public void scansWholeLinesAroundTheRange() {
		String text = "\tone\n\ttwo\tx\nthree\t\n\tfour\t";
		int two = text.indexOf("two");
		List<Integer> found = new ArrayList<>();
		// A range inside line two covers all of line two.
		IndentTabs.forEachTabWithoutStop(text, two, two + 1, 32, found::add);
		assertEquals(List.of(text.indexOf("\tx")), found);
		found.clear();
		// A range from line two into line three covers both.
		IndentTabs.forEachTabWithoutStop(text, two,
				text.indexOf("three") + 1, 32, found::add);
		assertEquals(List.of(text.indexOf("\tx"), text.indexOf("\t\n\tfour")),
				found);
		found.clear();
		// An empty range at the end covers the last line.
		IndentTabs.forEachTabWithoutStop(text, text.length(),
				text.length(), 32, found::add);
		assertEquals(List.of(text.length() - 1), found);
	}

	@Test
	public void removingTheTextBeforeATabMakesItLeading() {
		// "a\tb" became "\tb": the scan of the edited line finds nothing.
		assertEquals(List.of(), tabsWithoutStop("\tb", 32));
		assertEquals(List.of(1), tabsWithoutStop("a\tb", 32));
	}

	@Test
	public void rangesBeyondTheTextAreClamped() {
		List<Integer> found = new ArrayList<>();
		IndentTabs.forEachTabWithoutStop("a\tb", 10, 20, 32, found::add);
		assertEquals(List.of(1), found);
	}

	@Test
	public void lineBounds() {
		String text = "ab\ncd\n";
		assertEquals(0, IndentTabs.lineStart(text, 0));
		assertEquals(0, IndentTabs.lineStart(text, 2));
		assertEquals(3, IndentTabs.lineStart(text, 3));
		assertEquals(6, IndentTabs.lineStart(text, 6));
		assertEquals(2, IndentTabs.lineEnd(text, 0));
		assertEquals(2, IndentTabs.lineEnd(text, 2));
		assertEquals(5, IndentTabs.lineEnd(text, 3));
		assertEquals(6, IndentTabs.lineEnd(text, 6));
	}

	private static List<Integer> tabsWithoutStop(String text, int stops) {
		List<Integer> found = new ArrayList<>();
		IndentTabs.forEachTabWithoutStop(text, 0, text.length(), stops,
				found::add);
		return found;
	}
}
