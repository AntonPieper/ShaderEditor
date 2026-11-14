package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MovesTest {
	@Test
	public void aRenamedFileIsAtItsNewName() {
		assertEquals("scene.glsl", Moves.renamed("main.glsl", "main.glsl",
				"scene.glsl"));
	}

	@Test
	public void aFileInARenamedFolderMovesWithIt() {
		assertEquals("shared/noise/common.glsl", Moves.renamed(
				"lib/noise/common.glsl", "lib", "shared"));
	}

	@Test
	public void aNameThatOnlyStartsLikeTheFolderStays() {
		assertEquals("lib2/common.glsl", Moves.renamed("lib2/common.glsl",
				"lib", "shared"));
		assertFalse(Moves.within("lib2/common.glsl", "lib"));
		assertTrue(Moves.within("lib/common.glsl", "lib"));
	}
}
