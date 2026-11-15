package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FaceTest {
	private static final byte[] PNG = {1, 2, 3};

	// A picture wherever one is known: a shader that ran, what runs in
	// a folder, in the library, in the examples.
	@Test
	public void aKnownPictureStandsInFront() {
		for (Face.Of of : new Face.Of[]{Face.Of.LIBRARY, Face.Of.EXAMPLES,
				Face.Of.FOLDER, Face.Of.SINGLE_FILE, Face.Of.FILE}) {
			assertArrayEquals(of.name(), PNG, Face.of(of, PNG).png());
		}
		assertArrayEquals(PNG, Face.of(Row.Kind.SHADER, PNG).png());
	}

	// Without one, an icon of what it is.
	@Test
	public void elseAnIconOfWhatItIs() {
		assertIcon(Row.Picture.Icon.LIBRARY, Face.of(Face.Of.LIBRARY, null));
		assertIcon(Row.Picture.Icon.EXAMPLES,
				Face.of(Face.Of.EXAMPLES, new byte[0]));
		assertIcon(Row.Picture.Icon.FOLDER, Face.of(Row.Kind.FOLDER, null));
		assertIcon(Row.Picture.Icon.SHADER, Face.of(Row.Kind.TEXT, null));
		assertIcon(Row.Picture.Icon.SHADER,
				Face.of(Face.Of.SINGLE_FILE, null));
	}

	// An image is read small while its row is shown; the icon stands
	// until then. A picture kept for it is not shown instead.
	@Test
	public void anImageIsItsOwnPicture() {
		Row.Picture picture = Face.of(Row.Kind.IMAGE, PNG);
		assertTrue(picture.own());
		assertNull(picture.png());
		assertEquals(Row.Picture.Icon.IMAGE, picture.icon());
		assertFalse(Face.of(Row.Kind.SHADER, PNG).own());
		assertFalse(Face.of(Row.Kind.FOLDER, null).own());
	}

	// On its mat an image is whole, in its own proportions.
	@Test
	public void anImageIsShownWholeInItsProportions() {
		assertEquals(new Face.Placed(0, 0, 512, 384, 84, 63, false),
				Face.place(512, 384, 84));
		assertEquals(new Face.Placed(0, 0, 1080, 2400, 38, 84, false),
				Face.place(1080, 2400, 84));
		assertEquals(new Face.Placed(0, 0, 256, 256, 84, 84, false),
				Face.place(256, 256, 84));
	}

	// A strip shows its middle at 3:1.
	@Test
	public void aStripShowsItsMiddle() {
		assertEquals(new Face.Placed(300, 0, 600, 100, 84, 28, false),
				Face.place(900, 100, 84));
		assertEquals(new Face.Placed(0, 150, 50, 300, 28, 84, false),
				Face.place(50, 450, 84));
	}

	// A tiny image is drawn at a whole multiple of its size, crisp, so
	// that its pixels show.
	@Test
	public void aTinyImageShowsItsPixels() {
		assertEquals(new Face.Placed(0, 0, 16, 16, 80, 80, true),
				Face.place(16, 16, 84));
		assertEquals(new Face.Placed(0, 0, 84, 40, 84, 40, true),
				Face.place(84, 40, 84));
		assertEquals(new Face.Placed(0, 0, 1, 1, 84, 84, true),
				Face.place(0, 0, 84));
	}

	@Test
	public void showMoreHasNoPicture() {
		assertNull(Face.of(Row.Kind.MORE, PNG).png());
	}

	private static void assertIcon(Row.Picture.Icon icon,
			Row.Picture picture) {
		assertEquals(icon, picture.icon());
		assertNull(picture.png());
		assertEquals(0, picture.resource());
		assertFalse(picture.own());
	}
}
