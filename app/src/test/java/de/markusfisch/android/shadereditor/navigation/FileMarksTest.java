package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class FileMarksTest {
	private static final Level ROOT = Level.root("1");

	@Test
	public void aFileAndTheFoldersAboveItShowItsUnsavedText() {
		List<Row> rows = FileMarks.unsaved(rows(Reading.started().listed(
				List.of(new Reading.Entry("lib", true),
						new Reading.Entry("other", true),
						new Reading.Entry("main.glsl", false),
						new Reading.Entry("noise.glsl", false))), ROOT),
				Set.of("main.glsl", "lib/deep/common.glsl"));
		assertTrue(rows.get(0).marks().contains(Row.Mark.UNSAVED));
		assertTrue(rows.get(1).marks().isEmpty());
		assertTrue(rows.get(2).marks().contains(Row.Mark.UNSAVED));
		assertTrue(rows.get(3).marks().isEmpty());
	}

	@Test
	public void aFileIsFoundAtTheLevelOfItsFolder() {
		Level lib = ROOT.enter("lib");
		Row row = rows(Reading.started().listed(List.of(
				new Reading.Entry("common.glsl", false))), lib).get(0);
		assertEquals(new Item.Place(lib, row.key()),
				Item.Place.file("1", "lib/common.glsl"));
		assertEquals(new Item.Place(ROOT, "main.glsl"),
				Item.Place.file("1", "main.glsl"));
	}

	private static List<Row> rows(Reading reading, Level level) {
		return reading.rows(level, "more", "%1$d of %2$d");
	}
}
