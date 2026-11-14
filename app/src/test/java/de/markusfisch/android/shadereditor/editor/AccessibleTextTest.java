package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.AccessibleText.Segment;
import de.markusfisch.android.shadereditor.editor.AccessibleText.Unit;
import de.markusfisch.android.shadereditor.editor.AccessibleText.Window;

public class AccessibleTextTest {
	@Test
	public void aSmallTextIsOneWindow() {
		Text text = Text.of("void main() {\n}\n");
		assertEquals(new Window(0, text.length()),
				AccessibleText.window(text, 5, 5));
	}

	@Test
	public void theWindowIsTheBlockOfTheFocusAndOneOnEachSide() {
		Text text = lines(1000);
		int caret = text.lineStart(300) + 2;
		Window window = AccessibleText.window(text, caret, caret);
		assertEquals(text.lineStart(128), window.start());
		assertEquals(text.lineStart(512) - 1, window.end());
		// The window stays while the focus stays in its block.
		int other = text.lineStart(383);
		assertEquals(window, AccessibleText.window(text, other, other));
	}

	@Test
	public void longLinesAreCutAroundTheFocus() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 20; ++i) {
			sb.append("x".repeat(9999)).append('\n');
		}
		Text text = Text.of(sb);
		int caret = 100_000;
		Window window = AccessibleText.window(text, caret, caret);
		assertEquals(AccessibleText.MAX_CHARS, window.length());
		assertTrue(window.start() <= caret - AccessibleText.MAX_CHARS / 4);
		assertTrue(window.end() >= caret + AccessibleText.MAX_CHARS / 2);
		assertEquals(window, AccessibleText.window(text, caret + 1, caret + 1));
	}

	@Test
	public void aCutNeverSplitsASurrogatePair() {
		// Pairs start at odd offsets, so an even cut is inside one.
		String s = "a" + "\ud83c\udfa8".repeat(AccessibleText.MAX_CHARS);
		Text text = Text.of(s);
		Window window = AccessibleText.window(text, 40_000, 40_000);
		assertFalse(Character.isLowSurrogate(s.charAt(window.start())));
		assertFalse(Character.isLowSurrogate(s.charAt(window.end())));
	}

	@Test
	public void offsetsOutsideTheWindowCountAsItsEnds() {
		Window window = new Window(100, 200);
		assertEquals(0, window.index(5));
		assertEquals(50, window.index(150));
		assertEquals(100, window.index(5000));
	}

	@Test
	public void charactersAreClustersAndLineBreaks() {
		Text text = Text.of("e\u0301x\ny");
		assertEquals(List.of("e\u0301", "x", "\n", "y"),
				forward(text, Unit.CHARACTER));
		assertEquals(List.of("y", "\n", "x", "e\u0301"),
				backward(text, Unit.CHARACTER));
	}

	@Test
	public void wordsSkipWhiteSpaceAndLineBreaks() {
		Text text = Text.of("vec3 c  =a.xy;\n\n  return c;");
		List<String> words = List.of("vec3", "c", "=", "a", ".", "xy", ";",
				"return", "c", ";");
		assertEquals(words, forward(text, Unit.WORD));
		List<String> back = new ArrayList<>(words);
		Collections.reverse(back);
		assertEquals(back, backward(text, Unit.WORD));
	}

	@Test
	public void aWordFromInsideItIsItsRestOrItsStart() {
		Text text = Text.of("float value;");
		assertEquals(new Segment(8, 11), following(text, Unit.WORD, 8));
		assertEquals(new Segment(6, 8), preceding(text, Unit.WORD, 8));
	}

	@Test
	public void linesAreTheNextOrThePreviousWithTheirBreak() {
		Text text = Text.of("ab\ncd\nef");
		// From a line's start that line, from inside it the next.
		assertEquals(new Segment(0, 3), following(text, Unit.LINE, 0));
		assertEquals(new Segment(3, 6), following(text, Unit.LINE, 1));
		assertEquals(new Segment(6, 8), following(text, Unit.LINE, 6));
		assertNull(following(text, Unit.LINE, 7));
		// Backward the line before the caret's, the last at the end.
		assertEquals(new Segment(0, 3), preceding(text, Unit.LINE, 4));
		assertEquals(new Segment(6, 8), preceding(text, Unit.LINE, 8));
		assertEquals(new Segment(3, 6),
				preceding(Text.of("ab\ncd\n"), Unit.LINE, 6));
	}

	@Test
	public void paragraphsAreLinesThatAreNotEmpty() {
		Text text = Text.of("ab\n\n\ncd\n");
		assertEquals(List.of("ab", "cd"), forward(text, Unit.PARAGRAPH));
		assertEquals(new Segment(1, 2), following(text, Unit.PARAGRAPH, 1));
		assertEquals(new Segment(5, 6), preceding(text, Unit.PARAGRAPH, 6));
		assertEquals(new Segment(0, 2), preceding(text, Unit.PARAGRAPH, 5));
	}

	@Test
	public void aPageIsPageLines() {
		Text text = lines(10);
		assertEquals(new Segment(text.lineStart(2) + 1, text.lineStart(5)),
				AccessibleText.following(new Motion(), text, Unit.PAGE,
						text.lineStart(2) + 1, 3));
		assertEquals(new Segment(text.lineStart(4), text.lineStart(7)),
				AccessibleText.preceding(new Motion(), text, Unit.PAGE,
						text.lineStart(7), 3));
	}

	@Test
	public void blankTextHasNoWordsAndGivesUp() {
		Text text = Text.of("x" + "\n".repeat(AccessibleText.MAX_CHARS + 2));
		assertNull(following(text, Unit.WORD, 1));
		assertNull(following(text, Unit.PARAGRAPH, 1));
	}

	private static Text lines(int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; ++i) {
			sb.append("line ").append(i).append('\n');
		}
		return Text.of(sb);
	}

	private static Segment following(Text text, Unit unit, int offset) {
		return AccessibleText.following(new Motion(), text, unit, offset, 1);
	}

	private static Segment preceding(Text text, Unit unit, int offset) {
		return AccessibleText.preceding(new Motion(), text, unit, offset, 1);
	}

	// The segments a service hears moving through text from its start.
	private static List<String> forward(Text text, Unit unit) {
		List<String> heard = new ArrayList<>();
		for (int at = 0; ; ) {
			Segment s = following(text, unit, at);
			if (s == null) {
				return heard;
			}
			heard.add(text.substring(s.start(), s.end()));
			at = s.end();
		}
	}

	private static List<String> backward(Text text, Unit unit) {
		List<String> heard = new ArrayList<>();
		for (int at = text.length(); ; ) {
			Segment s = preceding(text, unit, at);
			if (s == null) {
				return heard;
			}
			heard.add(text.substring(s.start(), s.end()));
			at = s.start();
		}
	}
}
