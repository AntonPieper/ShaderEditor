package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SecondLineTest {
	@Test
	public void aNoticeHidesTheFrameRateUntilItsTime() {
		SecondLine line = new SecondLine();
		line.setFrameRate("60 fps");
		line.showNotice("Shader saved", 2000);
		line.setFrameRate("59 fps");
		assertEquals("Shader saved", line.shown(1999));
		assertEquals("59 fps", line.shown(2000));
	}
}
