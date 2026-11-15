package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A long name keeps its start and its end, the ending whole. What fits
 * is counted in characters here, as a fixed-width font would.
 */
public class NameCutTest {
	private static NameCut.Fits chars(int count) {
		return text -> text.codePointCount(0, text.length()) <= count;
	}

	@Test
	public void aNameThatFitsStaysWhole() {
		assertEquals("Aurora.frag", NameCut.cut("Aurora.frag", chars(11)));
	}

	// The start, the ellipsis, and the end: the last part of the stem
	// with the ending, about half of what is shown.
	@Test
	public void theStartAndTheEndStay() {
		String name = "A very long sketch name that shows the sunset.frag";
		String cut = NameCut.cut(name, chars(36));
		assertEquals("A very long sketc\u2026ws the sunset.frag", cut);
		assertTrue(cut.length() <= 36);
	}

	// The point of keeping the end: two names that differ only there
	// stay apart in a list.
	@Test
	public void namesThatDifferAtTheirEndStayApart() {
		String one = "Clouds over the bay at the end of the day sunset v1.frag";
		String two = "Clouds over the bay at the end of the day sunset v2.frag";
		for (int width = 10; width < one.length(); ++width) {
			assertNotEquals(String.valueOf(width),
					NameCut.cut(one, chars(width)),
					NameCut.cut(two, chars(width)));
		}
	}

	// The ending is whole in every width where one character of the
	// start fits beside it; narrower still, only the ending is shown.
	@Test
	public void theEndingIsNeverCut() {
		String name = "A shader whose name goes on long enough.frag.glsl";
		for (int width = 12; width < name.length(); ++width) {
			String cut = NameCut.cut(name, chars(width));
			assertTrue(cut, cut.endsWith(".frag.glsl"));
			assertTrue(cut, cut.length() <= width);
		}
		assertEquals("A\u2026.frag.glsl", NameCut.cut(name, chars(12)));
		assertEquals("\u2026.frag.glsl", NameCut.cut(name, chars(11)));
	}

	@Test
	public void theEndingIsWhatSaysWhatItIs() {
		assertEquals(".frag", NameCut.ending("Aurora.frag"));
		assertEquals(".frag.glsl", NameCut.ending("noise.frag.glsl"));
		assertEquals(".tar.gz", NameCut.ending("shaders.tar.gz"));
		// A version is no ending, nor a dot that starts a name, nor a
		// long or blank part.
		assertEquals(".frag", NameCut.ending("sunset v1.2.frag"));
		assertEquals("", NameCut.ending(".gitignore"));
		assertEquals("", NameCut.ending("version 1.2 notes"));
		assertEquals("", NameCut.ending("archive.something-long"));
		assertEquals("", NameCut.ending("README"));
	}

	// A name without an ending, as a folder's, is cut in its middle too.
	@Test
	public void aNameWithoutEndingIsCutInItsMiddle() {
		assertEquals("A very lon\u2026folder name",
				NameCut.cut("A very long folder name", chars(22)));
		assertEquals(".git\u2026nore", NameCut.cut(".gitignore", chars(9)));
	}

	// Under Running and in the New sheet a folder stands in front: it
	// shrinks first, then goes, then the name is cut.
	@Test
	public void theFolderInFrontShrinksFirst() {
		String path = "textures/A long picture name of the sunset.png";
		assertEquals("t\u2026res/A long picture name of the sunset.png",
				NameCut.cut(path, chars(43)));
		assertEquals("\u2026/A long picture name of the sunset.png",
				NameCut.cut(path, chars(39)));
		String cut = NameCut.cut(path, chars(25));
		assertTrue(cut, cut.startsWith("\u2026/A long"));
		assertTrue(cut, cut.endsWith("unset.png"));
		assertEquals("Ske\u2026hes / textures",
				NameCut.cut("Sketches / textures", chars(18)));
	}

	// Never half a character: an emoji, a flag or a letter with its
	// combining mark stands whole or not at all.
	@Test
	public void neverHalfACharacter() {
		String emoji = "Sunset 🌅🌅🌅" +
				"🌅 over the bay.frag";
		for (int width = 8; width < 40; ++width) {
			String cut = NameCut.cut(emoji, chars(width));
			for (int i = 0; i < cut.length(); ++i) {
				char c = cut.charAt(i);
				if (Character.isHighSurrogate(c)) {
					assertTrue(cut, i + 1 < cut.length() &&
							Character.isLowSurrogate(cut.charAt(i + 1)));
				} else if (Character.isLowSurrogate(c)) {
					assertTrue(cut, i > 0 &&
							Character.isHighSurrogate(cut.charAt(i - 1)));
				}
			}
		}
		// "e" with a combining acute: the mark never stands alone.
		String marks = "Café crème brûlée at the end.frag";
		for (int width = 8; width < marks.length(); ++width) {
			String cut = NameCut.cut(marks, chars(width));
			int ellipsis = cut.indexOf('\u2026');
			assertTrue(cut, ellipsis < 0 || ellipsis + 1 >= cut.length() ||
					Character.getType(cut.charAt(ellipsis + 1)) !=
							Character.NON_SPACING_MARK);
		}
	}

	// Right-to-left names are cut the same way, in their own order.
	@Test
	public void rightToLeftNamesKeepTheirEnd() {
		String name = "שקיעה מעל " +
				"הים בערב " +
				"אחרון.frag";
		String cut = NameCut.cut(name, chars(16));
		assertTrue(cut, cut.startsWith("שק"));
		assertTrue(cut, cut.endsWith("ן.frag"));
	}

	// No blank beside the ellipsis.
	@Test
	public void noBlankBesideTheEllipsis() {
		String cut = NameCut.cut("My sketch of the night sky.frag",
				chars(18));
		assertTrue(cut, !cut.contains(" \u2026") && !cut.contains("\u2026 "));
	}
}
