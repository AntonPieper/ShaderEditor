package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.OpenProject;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FileActionsTest {
	private static final Level ROOT = Level.root("k");
	private static final List<Row> ROWS = new Reading(Reading.Phase.LISTED,
			List.of(new Reading.Entry("lib", true),
					new Reading.Entry("main.frag", false),
					new Reading.Entry("noise.png", false)), null,
			Reading.PAGE).rows(ROOT, "more", "%1$d of %2$d");

	@Test
	public void aShaderFileThatDoesNotRunCanBeRun() {
		List<Row> rows = FileActions.of(ROWS, null, Map.of(), true);
		assertEquals(EnumSet.of(Row.Action.RUN, Row.Action.RENAME,
				Row.Action.DUPLICATE, Row.Action.DELETE), rows.get(1).actions());
		assertEquals(EnumSet.of(Row.Action.RENAME, Row.Action.DUPLICATE,
				Row.Action.DELETE), rows.get(2).actions());
		assertEquals(EnumSet.of(Row.Action.RENAME, Row.Action.DUPLICATE,
				Row.Action.DELETE), FileActions.of(ROWS, "main.frag", Map.of(), true)
				.get(1).actions());
	}

	@Test
	public void aFileKnownToDefineNoMainIsNotOfferedToRun() {
		assertEquals(EnumSet.of(Row.Action.RENAME, Row.Action.DUPLICATE,
				Row.Action.DELETE), FileActions.of(ROWS, null,
				Map.of("main.frag", OpenProject.Runs.NO), true).get(1)
				.actions());
		assertTrue(FileActions.offersRun(false, OpenProject.Runs.UNKNOWN));
		assertTrue(FileActions.offersRun(false, OpenProject.Runs.YES));
		assertFalse(FileActions.offersRun(true, OpenProject.Runs.YES));
	}

	@Test
	public void aFolderIsCreatedIn() {
		assertEquals(EnumSet.of(Row.Action.RENAME, Row.Action.NEW_HERE,
				Row.Action.DELETE), FileActions.of(ROWS, null, Map.of(), true).get(0)
				.actions());
	}

	@Test
	public void aFolderThatCannotChangeOffersOnlyToRun() {
		List<Row> rows = FileActions.of(ROWS, null, Map.of(), false);
		assertEquals(Set.of(), rows.get(0).actions());
		assertEquals(EnumSet.of(Row.Action.RUN), rows.get(1).actions());
	}
}
