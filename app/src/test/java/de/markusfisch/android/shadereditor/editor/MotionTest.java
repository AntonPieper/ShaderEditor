package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document.Selection;

public class MotionTest {
	// A surrogate pair, e with a combining acute accent, a woman with a
	// skin tone and a variation selector joined to a laptop, a flag.
	private static final String CHARS = "\ud83d\ude00" + "e\u0301" +
			"\ud83d\udc69\ud83c\udffd\u200d\ud83d\udcbb\ufe0f" +
			"\ud83c\udde9\ud83c\uddea" + "x";

	@Test
	public void charsAreWhatUsersSeeAsOne() {
		Text text = Text.of(CHARS);
		List<Integer> forward = new ArrayList<>();
		for (int i = 0; i < text.length(); ) {
			forward.add(i = Motion.nextChar(text, i));
		}
		assertEquals(List.of(2, 4, 12, 16, 17), forward);
		List<Integer> backward = new ArrayList<>();
		for (int i = text.length(); i > 0; ) {
			backward.add(i = Motion.previousChar(text, i));
		}
		assertEquals(List.of(16, 12, 4, 2, 0), backward);
	}

	@Test
	public void twoFlagsAreTwoChars() {
		Text text = Text.of("\ud83c\udde9\ud83c\uddea\ud83c\uddeb\ud83c\uddf7");
		assertEquals(4, Motion.nextChar(text, 0));
		assertEquals(4, Motion.previousChar(text, 8));
	}

	@Test
	public void aLineBreakIsOneChar() {
		Text text = Text.of("a\nb");
		assertEquals(2, Motion.nextChar(text, 1));
		assertEquals(1, Motion.previousChar(text, 2));
		assertEquals(3, Motion.nextChar(text, 3));
		assertEquals(0, Motion.previousChar(text, 0));
	}

	@Test
	public void nextWordSkipsSpaceThenOneWord() {
		Text text = Text.of("vec2 uv  += 1.0;");
		assertEquals(4, Motion.nextWord(text, 0));
		assertEquals(7, Motion.nextWord(text, 4));
		assertEquals(11, Motion.nextWord(text, 7));
		assertEquals(13, Motion.nextWord(text, 11));
	}

	@Test
	public void previousWordSkipsSpaceThenOneWord() {
		Text text = Text.of("vec2 uv  += 1.0;");
		assertEquals(9, Motion.previousWord(text, 11));
		assertEquals(5, Motion.previousWord(text, 9));
		assertEquals(0, Motion.previousWord(text, 3));
		assertEquals(0, Motion.previousWord(Text.of("  "), 2));
	}

	@Test
	public void wordMovesCrossLineEndsOneAtATime() {
		Text text = Text.of("a  \n  b");
		assertEquals(3, Motion.nextWord(text, 1));
		assertEquals(4, Motion.nextWord(text, 3));
		assertEquals(3, Motion.previousWord(text, 4));
		assertEquals(0, Motion.previousWord(text, 3));
	}

	@Test
	public void wordsKeepTheirMarks() {
		Text text = Text.of("cafe\u0301 x");
		assertEquals(5, Motion.nextWord(text, 0));
		assertEquals(0, Motion.previousWord(text, 5));
	}

	@Test
	public void lineStartAndEnd() {
		Text text = Text.of("ab\ncde\n");
		assertEquals(3, Motion.lineStart(text, 5));
		assertEquals(6, Motion.lineEnd(text, 4));
		assertEquals(7, Motion.lineEnd(text, 7));
	}

	@Test
	public void wordAtOffset() {
		Text text = Text.of("a = foo_1+bar;");
		assertEquals(new Selection(4, 9), Motion.wordAt(text, 6));
		// A word that ends at the offset wins over what follows.
		assertEquals(new Selection(4, 9), Motion.wordAt(text, 9));
		assertEquals(new Selection(10, 13), Motion.wordAt(text, 10));
		assertEquals(new Selection(13, 14), Motion.wordAt(text, 14));
		assertEquals(new Selection(0, 1), Motion.wordAt(text, 1));
		assertEquals(new Selection(3, 4), Motion.wordAt(text, 3));
		assertEquals(new Selection(3, 3), Motion.wordAt(Text.of("ab\n\n"), 3));
	}

	@Test
	public void tabsAdvanceToTheNextStop() {
		Text text = Text.of("\tx\nab\tx\nabcd\tx");
		assertEquals(4, Motion.column(text, 1, 4));
		assertEquals(4, Motion.column(text, 6, 4));
		assertEquals(8, Motion.column(text, 13, 4));
		assertEquals(2, Motion.column(text, 1, 2));
	}

	@Test
	public void marksTakeNoColumn() {
		assertEquals(2, Motion.column(Text.of("e\u0301x"), 3, 4));
	}

	@Test
	public void offsetAtTheClosestColumn() {
		Text text = Text.of("\tx");
		assertEquals(0, Motion.offsetAt(text, 0, 2, 4));
		assertEquals(1, Motion.offsetAt(text, 0, 3, 4));
		assertEquals(2, Motion.offsetAt(text, 0, 5, 4));
		assertEquals(2, Motion.offsetAt(text, 0, 40, 4));
	}
}
