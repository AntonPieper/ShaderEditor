package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.Motion;
import de.markusfisch.android.shadereditor.editor.Text;

public class TouchSelectionTest {
	private final TouchSelection touch = new TouchSelection(new Motion());

	@Test
	public void aPointInsideACharacterMeansItsNearerEnd() {
		// An e with an accent, two units, is one character.
		Text text = Text.of("line\naéx");
		// A layout that puts every unit 10 pixels wide and knows nothing
		// of characters.
		TouchSelection.Line layout = new TouchSelection.Line() {
			@Override
			public float xOf(int offset) {
				return offset * 10;
			}

			@Override
			public int offsetAt(float x) {
				return Math.round(x / 10);
			}
		};
		assertEquals(6, touch.offsetAt(text, 1, 14, layout));
		assertEquals(8, touch.offsetAt(text, 1, 21, layout));
		assertEquals(5, touch.offsetAt(text, 1, -30, layout));
		assertEquals(9, touch.offsetAt(text, 1, 500, layout));
	}

	@Test
	public void aDoubleTapSelectsTheWord() {
		Text text = Text.of("float value = 1.0;");
		assertEquals(new Selection(6, 11), touch.wordAt(text, 8));
	}

	@Test
	public void aDragByWordKeepsTheFirstWordAndTakesWholeWords() {
		Text text = Text.of("vec3 color = mix(a, b);\nreturn color;");
		touch.dragWords(text, 7);
		assertEquals(5, touch.anchor());
		assertEquals(10, touch.head());
		assertFalse(touch.dragTo(text, 8));
		assertTrue(touch.dragTo(text, 15));
		assertEquals(5, touch.anchor());
		assertEquals(16, touch.head());
		assertTrue(touch.dragTo(text, 27));
		assertEquals(30, touch.head());
		assertTrue(touch.dragTo(text, 2));
		assertEquals(10, touch.anchor());
		assertEquals(0, touch.head());
	}

	@Test
	public void aHandleMovesItsEndAndNeverEmptiesTheSelection() {
		Text text = Text.of("float a;");
		touch.dragHandle(2, 5);
		assertTrue(touch.dragTo(text, 0));
		assertEquals(2, touch.anchor());
		assertEquals(0, touch.head());
		assertFalse(touch.dragTo(text, 2));
		assertEquals(0, touch.head());
		// The caret's handle moves the caret.
		touch.dragHandle(3, 3);
		assertTrue(touch.dragTo(text, 7));
		assertEquals(7, touch.anchor());
		assertEquals(7, touch.head());
	}

	@Test
	public void aHandleTakesAWordWholeOnceTheFingerIsPastItsMiddle() {
		Text text = Text.of("float alpha = beta;");
		touch.dragHandle(0, 5);
		// Into the first half of alpha: up to its start.
		assertTrue(touch.dragTo(text, 7));
		assertEquals(6, touch.head());
		// Past its middle: all of it.
		assertTrue(touch.dragTo(text, 9));
		assertEquals(11, touch.head());
		assertFalse(touch.dragTo(text, 10));
	}

	@Test
	public void aHandleMovedBackGoesByCharacterUntilAWordEnds() {
		Text text = Text.of("float alpha = beta;");
		touch.dragHandle(0, 5);
		touch.dragTo(text, 9);
		assertEquals(11, touch.head());
		// Back by two, keeping the distance to the finger.
		assertTrue(touch.dragTo(text, 7));
		assertEquals(9, touch.head());
		// Forward again by character, not to the end of the word.
		assertTrue(touch.dragTo(text, 8));
		assertEquals(10, touch.head());
		assertTrue(touch.dragTo(text, 9));
		assertEquals(11, touch.head());
		// At the end of a word, the next one joins whole again.
		assertTrue(touch.dragTo(text, 16));
		assertEquals(18, touch.head());
	}

	@Test
	public void theMouseSelectsByCharacterAndMayEmptyTheSelection() {
		Text text = Text.of("float alpha;");
		touch.dragChars(2, 2);
		assertTrue(touch.dragTo(text, 8));
		assertEquals(2, touch.anchor());
		assertEquals(8, touch.head());
		assertTrue(touch.dragTo(text, 2));
		assertEquals(2, touch.head());
	}

	@Test
	public void handlesAndToolbarWaitForTheFingerToLiftAfterAWordDrag() {
		Text text = Text.of("float alpha = beta;");
		assertTrue(touch.showsHandles());
		assertTrue(touch.showsToolbar());
		touch.dragWords(text, 7);
		touch.dragTo(text, 16);
		assertFalse(touch.showsHandles());
		assertFalse(touch.showsToolbar());
		touch.drop();
		assertTrue(touch.showsHandles());
		assertTrue(touch.showsToolbar());
	}

	@Test
	public void aDraggedHandleShowsButTheToolbarDoesNot() {
		touch.dragHandle(0, 5);
		assertTrue(touch.showsHandles());
		assertFalse(touch.showsToolbar());
		touch.drop();
		assertTrue(touch.showsToolbar());
	}

	@Test
	public void theMagnifierStaysOverTheLineAndHidesFarFromIt() {
		assertEquals(50, TouchSelection.magnifierX(50, 20, 200, 40), 0);
		assertEquals(20, TouchSelection.magnifierX(0, 20, 200, 40), 0);
		assertEquals(200, TouchSelection.magnifierX(230, 20, 200, 40), 0);
		assertTrue(Float.isNaN(TouchSelection.magnifierX(241, 20, 200, 40)));
		assertTrue(Float.isNaN(TouchSelection.magnifierX(-21, 20, 200, 40)));
	}

	@Test
	public void droppedTextMovesUnlessDroppedInsideItself() {
		// "ab[cd]ef": cd dragged.
		assertEquals(0, TouchSelection.dropAt(2, 4, 0));
		assertEquals(4, TouchSelection.dropAt(2, 4, 6));
		assertEquals(-1, TouchSelection.dropAt(2, 4, 3));
		assertEquals(-1, TouchSelection.dropAt(2, 4, 4));
	}

	@Test
	public void theShadowShowsTheFirstLineUpToTwentyOneCharacters() {
		Text text = Text.of("\nvec3 color = mix(low, high, t);\nreturn;");
		// The line break before it is skipped, the rest of the line cut.
		assertEquals(new Selection(1, 22), touch.shadowOf(text, 0, 40));
		// A short selection, all of it.
		assertEquals(new Selection(6, 11), touch.shadowOf(text, 6, 11));
		// Only the first line.
		assertEquals(new Selection(17, 32), touch.shadowOf(text, 17, 40));
	}
}
