package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.navigation.Row.Picture.Icon;
import de.markusfisch.android.shadereditor.navigation.Row.Picture.Spoken;

/** What stands in front of a row, by what it is and what is known. */
public class FaceTest {
	private static final byte[] PNG = {1, 2, 3};

	// A shader that ran shows its picture edge to edge, wherever it is.
	@Test
	public void aShaderThatRanShowsItsPicture() {
		Row.Picture face = Face.of(Row.Kind.TEXT, "Aurora.frag", PNG);
		assertArrayEquals(PNG, face.png());
		assertFalse(face.frame());
		assertEquals(Spoken.SHADER, face.spoken());
		assertArrayEquals(PNG, Face.of(Face.Of.SINGLE_FILE, "wave.frag", PNG,
				true).png());
	}

	// One that never ran shows the empty place of its picture, with
	// nothing in it: the play symbol means only "this runs now".
	@Test
	public void aShaderThatNeverRanIsAnEmptyFrame() {
		for (String name : new String[]{"Grüße.frag", "blur.FS", "x.fsh"}) {
			Row.Picture face = Face.of(Row.Kind.TEXT, name, null);
			assertTrue(name, face.frame());
			assertNull(face.png());
			assertFalse(face.own());
			assertEquals(Spoken.SHADER_NOT_RUN, face.spoken());
		}
	}

	// Text without a picture: a stock icon without a tile, no letters.
	@Test
	public void textWithoutAPictureShowsTheIconOfItsKind() {
		assertFace(Icon.CODE, Spoken.INCLUDE, false,
				Face.of(Row.Kind.TEXT, "sdf.glsl", null));
		assertFace(Icon.SETTINGS, Spoken.SETTINGS, false,
				Face.of(Row.Kind.TEXT, "shader-project.json", null));
		assertFace(Icon.DATA, Spoken.JSON, false,
				Face.of(Row.Kind.TEXT, "palette.json", null));
		assertFace(Icon.NOTES, Spoken.MARKDOWN, false,
				Face.of(Row.Kind.TEXT, "README.md", null));
		assertFace(Icon.FILE, Spoken.FILE, false,
				Face.of(Row.Kind.TEXT, "LICENSE", null));
		assertFace(Icon.FILE, Spoken.FILE, false,
				Face.of(Row.Kind.TEXT, "notes.txt", null));
	}

	// Another shading language is code this app does not run: dimmed.
	@Test
	public void anotherLanguageIsDimmedCode() {
		assertFace(Icon.CODE, Spoken.OTHER_LANGUAGE, true,
				Face.of(Row.Kind.TEXT, "blur.wgsl", null));
	}

	// A file the editor cannot open is dimmed and says so.
	@Test
	public void aFileThatCannotBeOpenedIsDimmed() {
		assertFace(Icon.FILE, Spoken.CANNOT_OPEN, true,
				Face.of(Row.Kind.OTHER, "data.bin", null));
		assertFace(Icon.IMAGE, Spoken.CANNOT_OPEN, true,
				Face.of(Face.Of.IMAGE, "huge.png", null, false));
		assertFace(Icon.FILE, Spoken.CANNOT_OPEN, true,
				Face.of(Face.Of.FILE, "huge.frag", PNG, false));
	}

	// An image is its own picture, framed on a mat, read while its row
	// is shown; a picture kept for it is not shown instead.
	@Test
	public void anImageIsItsOwnPicture() {
		Row.Picture face = Face.of(Row.Kind.IMAGE, "noise.png", PNG);
		assertTrue(face.own());
		assertNull(face.png());
		assertFalse(face.frame());
		assertEquals(Icon.IMAGE, face.icon());
		assertEquals(Spoken.IMAGE, face.spoken());
	}

	// A folder, also a place on the top level, shows the picture of
	// what runs in it, else the folder icon.
	@Test
	public void aFolderShowsItsShader() {
		assertArrayEquals(PNG, Face.of(Row.Kind.FOLDER, "Ink", PNG).png());
		assertFace(Icon.FOLDER, Spoken.FOLDER, false,
				Face.of(Row.Kind.FOLDER, "lib", null));
		assertFace(Icon.LIBRARY, Spoken.FOLDER, false,
				Face.of(Face.Of.LIBRARY, null, null, true));
		assertFace(Icon.EXAMPLES, Spoken.FOLDER, false,
				Face.of(Face.Of.EXAMPLES, null, new byte[0], true));
		assertEquals(Spoken.FOLDER,
				Face.of(Face.Of.LIBRARY, null, PNG, true).spoken());
	}

	@Test
	public void showMoreHasNoFace() {
		Row.Picture face = Face.of(Row.Kind.MORE, "Show more", PNG);
		assertNull(face.png());
		assertEquals(Spoken.NONE, face.spoken());
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

	private static void assertFace(Icon icon, Spoken spoken, boolean dim,
			Row.Picture face) {
		assertEquals(icon, face.icon());
		assertEquals(spoken, face.spoken());
		assertEquals(dim, face.dim());
		assertNull(face.png());
		assertFalse(face.own());
		assertFalse(face.frame());
		assertEquals(0, face.resource());
	}
}
