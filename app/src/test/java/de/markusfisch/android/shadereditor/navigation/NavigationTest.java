package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class NavigationTest {
	@Test
	public void aTapRunsAShaderThatIsNotInUse() {
		assertEquals(Tap.OPEN_AND_RUN, Tap.of(Row.Kind.SHADER, false));
	}

	@Test
	public void aTapOnlyOpensWhatTheRunningShaderUses() {
		assertEquals(Tap.OPEN, Tap.of(Row.Kind.SHADER, true));
		assertEquals(Tap.OPEN, Tap.of(Row.Kind.TEXT, false));
	}

	@Test
	public void aTapOnAFolderOrImageNeverOpensTheEditor() {
		assertEquals(Tap.ENTER, Tap.of(Row.Kind.FOLDER, false));
		assertEquals(Tap.SHOW_IMAGE, Tap.of(Row.Kind.IMAGE, true));
		assertEquals(Tap.NOTHING, Tap.of(Row.Kind.OTHER, false));
	}

	@Test
	public void lastChangedComesFirst() {
		List<Row> rows = Order.CHANGED.sort(List.of(
				shader("a", "Old", 1), shader("b", "New", 3),
				shader("c", "Middle", 2)));
		assertEquals(List.of("New", "Middle", "Old"), names(rows));
	}

	@Test
	public void byNameIgnoresCaseAndPutsFoldersFirst() {
		List<Row> rows = Order.BY_NAME.sort(List.of(
				shader("a", "beta", 1), shader("b", "Alpha", 2),
				folder("c", "zeta")));
		assertEquals(List.of("zeta", "Alpha", "beta"), names(rows));
	}

	@Test
	public void theFilterKeepsMatchingNamesAndDropsEmptySections() {
		Listing listing = listing(
				new Listing.Section("Start here", List.of(
						shader("a", "Shapes", 0), shader("b", "Loop", 0))),
				new Listing.Section("Device inputs", List.of(
						shader("c", "Tilt", 0))));
		Listing filtered = listing.filter("SHA");
		assertEquals(1, filtered.size());
		assertEquals("Start here", filtered.sections().get(0).heading());
		assertEquals(3, listing.filter(" ").size());
	}

	@Test
	public void sectionsWithHeadingsComeBeforeTheirRows() {
		List<Item> items = Item.of(listing(
				new Listing.Section(null, List.of(shader("a", "One", 0))),
				new Listing.Section("Recent", List.of(shader("b", "Two", 0)))),
				Level.LIBRARY, null, null, Set.of());
		assertEquals(3, items.size());
		assertNull(items.get(0).heading());
		assertEquals("Recent", items.get(1).heading());
		assertEquals("Two", items.get(2).row().name());
	}

	@Test
	public void theOpenFileIsSelectedAndTheRunningOneMarked() {
		Item.Place open = new Item.Place(Level.LIBRARY, "b");
		List<Item> items = Item.of(listing(new Listing.Section(null, List.of(
						shader("a", "One", 0), shader("b", "Two", 0)))),
				Level.LIBRARY, open, open, Set.of());
		assertFalse(items.get(0).selected());
		assertEquals(Tap.OPEN_AND_RUN, items.get(0).tap());
		assertTrue(items.get(1).selected());
		assertEquals(Set.of(Row.Mark.RUNNING), items.get(1).marks());
		assertEquals(Tap.OPEN, items.get(1).tap());
	}

	@Test
	public void aShaderTheRunningOneUsesOnlyOpens() {
		Item.Place running = new Item.Place(Level.LIBRARY, "main");
		Item.Place pass = new Item.Place(Level.LIBRARY, "pass");
		List<Item> items = Item.of(listing(new Listing.Section(null, List.of(
						shader("pass", "Pass", 0), shader("other", "Other", 0)))),
				Level.LIBRARY, running, running, Set.of(pass));
		assertEquals(Tap.OPEN, items.get(0).tap());
		assertEquals(Set.of(), items.get(0).marks());
		assertEquals(Tap.OPEN_AND_RUN, items.get(1).tap());
	}

	@Test
	public void theSameKeyInAnotherLevelIsAnotherFile() {
		Item.Place open = new Item.Place(Level.LIBRARY, "a");
		List<Item> items = Item.of(listing(new Listing.Section(null,
						List.of(shader("a", "One", 0)))),
				Level.EXAMPLES, open, open, Set.of());
		assertFalse(items.get(0).selected());
		assertEquals(Tap.OPEN_AND_RUN, items.get(0).tap());
	}

	@Test
	public void theFolderHoldingTheOpenFileIsSelected() {
		Item.Place open = new Item.Place(Level.LIBRARY, "a");
		List<Item> items = Item.of(listing(new Listing.Section(null,
						List.of(folder("library", "Shaders")))),
				Level.FOLDERS, open, open, Set.of());
		assertTrue(items.get(0).selected());
		assertEquals(Tap.ENTER, items.get(0).tap());
	}

	@Test
	public void levelsKnowWhatIsAboveThem() {
		assertTrue(Level.LIBRARY.isBelow(Level.FOLDERS));
		assertFalse(Level.FOLDERS.isBelow(Level.LIBRARY));
		assertFalse(Level.EXAMPLES.isBelow(Level.LIBRARY));
	}

	private static Listing listing(Listing.Section... sections) {
		return new Listing("Shaders", null, false, true, Listing.Bar.NEW,
				List.of(sections), null);
	}

	private static Row shader(String key, String name, long changed) {
		return new Row(key, Row.Kind.SHADER, Row.Picture.png(null), name,
				null, changed, null, EnumSet.noneOf(Row.Mark.class),
				EnumSet.noneOf(Row.Action.class));
	}

	private static Row folder(String key, String name) {
		return new Row(key, Row.Kind.FOLDER,
				Row.Picture.of(Row.Picture.Icon.LIBRARY), name, null, 0,
				Level.LIBRARY, EnumSet.noneOf(Row.Mark.class),
				EnumSet.noneOf(Row.Action.class));
	}

	private static List<String> names(List<Row> rows) {
		return rows.stream().map(Row::name).toList();
	}
}
