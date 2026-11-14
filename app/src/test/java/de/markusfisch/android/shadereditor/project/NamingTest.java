package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class NamingTest {
	private final Naming naming = new Naming(List.of("main.frag",
			"Untitled.glsl", "lib"));

	@Test
	public void aNameThatDiffersOnlyInCaseIsTaken() {
		assertEquals(Naming.Problem.TAKEN, naming.check("MAIN.frag"));
		assertEquals(Naming.Problem.TAKEN, naming.check("lib"));
		assertEquals(Naming.Problem.NONE, naming.check("common.glsl"));
	}

	@Test
	public void aNameCannotHoldASlashOrWhatFoldersRefuse() {
		assertEquals(Naming.Problem.INVALID_CHARACTER,
				naming.check("lib/common.glsl"));
		assertEquals(Naming.Problem.INVALID_CHARACTER, naming.check("a:b"));
		assertEquals(Naming.Problem.INVALID_CHARACTER,
				naming.check("a\u0007b"));
	}

	@Test
	public void aNameMustSaySomethingAndBeShown() {
		assertEquals(Naming.Problem.EMPTY, naming.check("  "));
		assertEquals(Naming.Problem.HIDDEN, naming.check(".hidden"));
	}

	@Test
	public void theSuggestedNameIsTheFirstNotTaken() {
		assertEquals("Untitled 2.glsl", naming.unused("Untitled", ".glsl"));
		assertEquals("Folder", naming.unused("Folder", ""));
	}

	@Test
	public void aCopyKeepsItsExtension() {
		assertEquals("main copy.frag", naming.copyOf("main.frag"));
		assertEquals("lib copy", naming.copyOf("lib"));
		assertEquals("main copy 2.frag", new Naming(List.of("main.frag",
				"main copy.frag")).copyOf("main.frag"));
	}

	@Test
	public void makesAFileNameStemFromAnyTitle() {
		assertEquals("Plasma", Naming.stem("Plasma", "Untitled"));
		assertEquals("Plasma", Naming.stem("  Plasma \t", "Untitled"));
		assertEquals("a_b_c_d_e_f_g_h_i_j",
				Naming.stem("a/b\\c\"d:e*f?g<h>i|j", "Untitled"));
		assertEquals("two_lines", Naming.stem("two\nlines", "Untitled"));
		assertEquals("hidden", Naming.stem("...hidden", "Untitled"));
		assertEquals("2026-11-14 10_22_31",
				Naming.stem("2026-11-14 10:22:31", "Untitled"));
	}

	@Test
	public void aShaderEndingIsTakenOffAStem() {
		assertEquals("plasma", Naming.stem("plasma.glsl", "Untitled"));
		assertEquals("plasma", Naming.stem("plasma.FRAG", "Untitled"));
		assertEquals("plasma.txt", Naming.stem("plasma.txt", "Untitled"));
		assertEquals("v1.2", Naming.stem("v1.2", "Untitled"));
		// A name that starts with a dot has no ending to take off.
		assertEquals("frag", Naming.stem(".frag", "Untitled"));
	}

	@Test
	public void nothingLeftOfATitleIsTheFallback() {
		assertEquals("Untitled", Naming.stem(null, "Untitled"));
		assertEquals("Untitled", Naming.stem("", "Untitled"));
		assertEquals("Untitled", Naming.stem("  .. ", "Untitled"));
		assertEquals("Ohne Titel", Naming.stem(" ", "Ohne Titel"));
	}

	@Test
	public void aLongTitleIsCutWithoutSplittingACharacter() {
		String long1 = "x".repeat(250);
		assertEquals(100, Naming.stem(long1, "Untitled").length());
		String emoji = "y".repeat(99) + "\ud83d\ude00" + "z";
		assertEquals("y".repeat(99), Naming.stem(emoji, "Untitled"));
	}

	// A phone's storage takes names of 255 bytes, not characters.
	@Test
	public void aLongTitleInAnyScriptFitsAFileName() {
		for (String title : new String[]{"\u754c".repeat(100),
				"\ud83d\ude00".repeat(100), "\u00e4".repeat(100),
				"x".repeat(100)}) {
			String name = new Naming(List.of()).unused(
					Naming.stem(title, "Untitled"), " 999.frag");
			assertTrue(title, name.getBytes(
					java.nio.charset.StandardCharsets.UTF_8).length <= 255);
			// Cut between characters, never inside one.
			assertEquals(title, name, new String(name.getBytes(
					java.nio.charset.StandardCharsets.UTF_8),
					java.nio.charset.StandardCharsets.UTF_8));
			assertTrue(title, Naming.stem(title, "Untitled").length() > 40);
		}
		assertEquals(100, Naming.stem("x".repeat(250), "U").length());
	}

	@Test
	public void everyStemGivesANameThatIsValid() {
		for (String title : new String[]{"a/b", "  .x ", "con", "ä ö",
				"a\u0301", "x".repeat(300), "plasma.fs"}) {
			String name = new Naming(List.of()).unused(
					Naming.stem(title, "Untitled"), ".frag");
			assertEquals(title, Naming.Problem.NONE,
					new Naming(List.of()).check(name));
			assertEquals(title, name,
					ProjectPath.parse(name).value().toString());
		}
	}

	@Test
	public void aTakenNameIsNotGivenAgain() {
		Naming naming = new Naming(List.of("Plasma.frag"));
		String second = naming.unused("plasma", ".frag");
		assertEquals("plasma 2.frag", second);
		naming.take(second);
		assertEquals("Plasma 3.frag", naming.unused("Plasma", ".frag"));
	}
}
