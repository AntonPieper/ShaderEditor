package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
		assertIcon(Row.Picture.Icon.IMAGE, Face.of(Row.Kind.IMAGE, null));
		assertIcon(Row.Picture.Icon.SHADER, Face.of(Row.Kind.TEXT, null));
		assertIcon(Row.Picture.Icon.SHADER,
				Face.of(Face.Of.SINGLE_FILE, null));
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
	}
}
