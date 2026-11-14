package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FileLineTest {
	@Test
	public void theFileThatRunsSaysOnlyItsFolder() {
		assertEquals("aurora", FileLine.of("aurora", "main.glsl",
				"main.glsl"));
		assertEquals("aurora / lib / noise", FileLine.of("aurora",
				"lib/noise/common.glsl", null));
	}

	@Test
	public void anotherFileThatRunsComesBeforeTheFolder() {
		assertEquals("▶ main.glsl · aurora / lib",
				FileLine.of("aurora", "lib/common.glsl", "main.glsl"));
	}
}
