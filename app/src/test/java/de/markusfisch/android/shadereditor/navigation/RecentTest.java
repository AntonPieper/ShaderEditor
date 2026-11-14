package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class RecentTest {
	@Test
	public void theOneOpenedLastComesFirst() {
		List<Row> rows = Recent.rows(List.of(
				folder("a", "Old", 1, false),
				folder("b", "New", 3, false),
				folder("c", "Middle", 2, false)), "single file", "Cannot be opened");
		assertEquals(List.of("New", "Middle", "Old"),
				rows.stream().map(Row::name).toList());
	}

	@Test
	public void aSingleFileSaysSoAfterWhereItIs() {
		List<Row> rows = Recent.rows(List.of(
				new Recent("a", true, "wave.glsl", "Downloads", 2, false,
						false),
				new Recent("b", true, "lone.glsl", "", 1, false, false),
				folder("c", "Shaders", 0, false)), "single file",
				"Cannot be opened");
		assertEquals("Downloads \u00b7 single file", rows.get(0).detail());
		assertEquals("single file", rows.get(1).detail());
		assertEquals("Phone", rows.get(2).detail());
	}

	@Test
	public void aFolderWithUnsavedTextIsMarked() {
		List<Row> rows = Recent.rows(List.of(
				folder("a", "Kept", 2, true),
				folder("b", "Clean", 1, false)), "single file", "Cannot be opened");
		assertEquals(Set.of(Row.Mark.UNSAVED), rows.get(0).marks());
		assertEquals(Set.of(), rows.get(1).marks());
	}

	@Test
	public void aTapGoesIntoWhatWasOpened() {
		Row row = Recent.rows(List.of(folder("abcd1234", "Kept", 0,
				false)), "single file", "Cannot be opened").get(0);
		assertEquals(Tap.ENTER, Tap.of(row.kind(), false));
		assertEquals(Level.root("abcd1234"), row.enters());
	}

	@Test
	public void aLostFolderSaysSoFirstAndCanBeRemoved() {
		Row row = Recent.rows(List.of(new Recent("a", false, "Gone",
				"Phone", 0, false, true)), "single file",
				"Cannot be opened").get(0);
		assertEquals("Cannot be opened \u00b7 Phone", row.detail());
		assertEquals(Set.of(Row.Action.REMOVE), row.actions());
	}

	@Test
	public void aFolderWithoutAPlaceHasNoSecondLine() {
		assertNull(Recent.rows(List.of(new Recent("a", false, "Files", "",
				0, false, false)), "single file", "Cannot be opened").get(0).detail());
	}

	private static Recent folder(String key, String name, long opened,
			boolean unsaved) {
		return new Recent(key, false, name, "Phone", opened, unsaved,
				false);
	}
}
