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
		touch.dragChars(2, 5);
		assertTrue(touch.dragTo(text, 0));
		assertEquals(2, touch.anchor());
		assertEquals(0, touch.head());
		assertFalse(touch.dragTo(text, 2));
		assertEquals(0, touch.head());
		// The caret's handle moves the caret.
		touch.dragChars(3, 3);
		assertTrue(touch.dragTo(text, 7));
		assertEquals(7, touch.anchor());
		assertEquals(7, touch.head());
	}
}
