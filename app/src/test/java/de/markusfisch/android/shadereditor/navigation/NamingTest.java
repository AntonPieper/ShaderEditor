package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public class NamingTest {
	private final Naming naming = new Naming(List.of("main.glsl",
			"Untitled.glsl", "lib"));

	@Test
	public void aNameThatDiffersOnlyInCaseIsTaken() {
		assertEquals(Naming.Problem.TAKEN, naming.check("MAIN.glsl"));
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
		assertEquals("main copy.glsl", naming.copyOf("main.glsl"));
		assertEquals("lib copy", naming.copyOf("lib"));
		assertEquals("main copy 2.glsl", new Naming(List.of("main.glsl",
				"main copy.glsl")).copyOf("main.glsl"));
	}
}
