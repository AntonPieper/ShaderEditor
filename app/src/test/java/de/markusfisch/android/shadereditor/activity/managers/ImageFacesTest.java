package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ImageFacesTest {
	// An image is read at the largest step that keeps its shorter side
	// at a row's size or more, so a photo never needs its full memory.
	@Test
	public void readsLargeImagesAtAFraction() {
		assertEquals(1, ImageFaces.sample(128, 128));
		assertEquals(1, ImageFaces.sample(255, 4000));
		assertEquals(2, ImageFaces.sample(256, 256));
		assertEquals(2, ImageFaces.sample(640, 480));
		assertEquals(16, ImageFaces.sample(4032, 3024));
		assertEquals(1, ImageFaces.sample(0, 0));
	}
}
