package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FileLineTest {
	@Test
	public void theFileThatRunsSaysOnlyItsFolder() {
		assertEquals("aurora", FileLine.of("aurora", "main.frag",
				"main.frag"));
		assertEquals("aurora / lib / noise", FileLine.of("aurora",
				"lib/noise/common.glsl", null));
	}

	@Test
	public void anotherFileThatRunsComesBeforeTheFolder() {
		assertEquals("▶ main.frag · aurora / lib",
				FileLine.of("aurora", "lib/common.glsl", "main.frag"));
	}
}
