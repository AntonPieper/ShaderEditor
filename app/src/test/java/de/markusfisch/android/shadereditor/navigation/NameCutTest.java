package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** A long name keeps its ending; what fits is counted in chars here. */
public class NameCutTest {
	private static NameCut.Fits chars(int count) {
		return text -> text.length() <= count;
	}

	@Test
	public void aNameThatFitsStaysWhole() {
		assertEquals("Aurora.frag", NameCut.cut("Aurora.frag", chars(11)));
	}

	@Test
	public void theStemIsCutAndTheEndingStays() {
		String name = "A very long sketch name that goes on and on.frag";
		assertEquals("A very long sketch na\u2026.frag",
				NameCut.cut(name, chars(27)));
	}

	@Test
	public void aNameWithoutEndingIsCutAtItsEnd() {
		assertEquals("A very long fol\u2026",
				NameCut.cut("A very long folder name", chars(16)));
		// A dot that starts the name is no ending, nor is a long one.
		assertEquals(".gi\u2026", NameCut.cut(".gitignore", chars(4)));
		assertEquals("", NameCut.ending("version 1.2 notes"));
		assertEquals("", NameCut.ending("archive.something-long"));
		assertEquals(".glsl", NameCut.ending("noise.glsl"));
	}

	@Test
	public void withoutRoomForTheEndingTheNameIsCutAtItsEnd() {
		assertEquals("Long\u2026", NameCut.cut("Longname.frag", chars(5)));
		assertEquals("\u2026", NameCut.cut("Longname.frag", chars(1)));
	}

	@Test
	public void neverHalfACharacterNorABlankBeforeTheEllipsis() {
		// The emoji is two chars; it is never split.
		assertEquals("ab\u2026.frag", NameCut.cut("ab\uD83C\uDF0Ccd.frag",
				chars(9)));
		assertEquals("My\u2026.frag", NameCut.cut("My sketch.frag",
				chars(9)));
	}
}
