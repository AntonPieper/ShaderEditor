package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TapTest {
	@Test
	public void aTextNamedLikeAPictureOpensAsText() {
		assertEquals(Tap.OPEN, Tap.of(Row.Kind.IMAGE, false).read(false));
	}

	@Test
	public void aPictureNamedLikeAShaderShowsAsAPicture() {
		assertEquals(Tap.SHOW_IMAGE, Tap.of(Row.Kind.TEXT, false).read(true));
		assertEquals(Tap.SHOW_IMAGE,
				Tap.of(Row.Kind.SHADER, false).read(true));
	}

	@Test
	public void aTextKeepsWhatItsTapDoes() {
		assertEquals(Tap.OPEN_AND_RUN,
				Tap.of(Row.Kind.SHADER, false).read(false));
		assertEquals(Tap.OPEN, Tap.of(Row.Kind.TEXT, false).read(false));
	}
}
