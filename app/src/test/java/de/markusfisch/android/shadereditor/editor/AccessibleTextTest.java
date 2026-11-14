package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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

	private static Text lines(int count) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < count; ++i) {
			sb.append("line ").append(i).append('\n');
		}
		return Text.of(sb);
	}
}
