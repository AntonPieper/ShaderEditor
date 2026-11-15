package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ReadingTest {
	private static final Level ROOT = Level.root("abcd1234");

	@Test
	public void aFolderLevelKnowsItsFolderAndComesBackFromItsName() {
		Level inner = ROOT.enter("lib").enter("noise");
		assertEquals("abcd1234", inner.key());
		assertEquals("lib/noise", inner.path());
		assertNull(ROOT.path());
		assertNull(Level.FOLDERS.key());
		assertEquals(inner, Level.of(inner.name()));
		assertEquals(Level.FOLDERS, Level.of(Level.FOLDERS.name()));
		// The examples are a folder like any other: no level of their own.
		assertNull(Level.of("examples"));
		assertEquals(Level.FOLDERS, inner.up().up().up());
	}

	@Test
	public void aFolderLeadsIntoItsLevelAndAFileIsKnownByItsPath() {
		List<Row> rows = listed(new Reading.Entry("lib", true),
				new Reading.Entry("main.frag", false),
				new Reading.Entry("Paper.PNG", false))
				.rows(ROOT.enter("sub"), "Show more", "%1$d of %2$d");
		assertEquals(ROOT.enter("sub").enter("lib"), rows.get(0).enters());
		assertEquals(Tap.ENTER, Tap.of(rows.get(0).kind(), false));
		assertEquals("sub/main.frag", rows.get(1).key());
		assertEquals(Row.Kind.TEXT, rows.get(1).kind());
		assertEquals(Row.Kind.IMAGE, rows.get(2).kind());
	}

	@Test
	public void namesThatStartWithADotAreLeftOut() {
		List<Row> rows = listed(new Reading.Entry(".git", true),
				new Reading.Entry("main.frag", false))
				.rows(ROOT, "Show more", "%1$d of %2$d");
		assertEquals(1, rows.size());
		assertEquals("main.frag", rows.get(0).name());
	}

	@Test
	public void rowsComeInPagesWithARowForTheRest() {
		List<Reading.Entry> entries = new ArrayList<>();
		for (int i = 0; i < 300; ++i) {
			entries.add(new Reading.Entry("f" + i + ".glsl", false));
		}
		Reading reading = Reading.started().listed(entries);
		List<Row> rows = reading.rows(ROOT, "Show more", "%1$d of %2$d");
		assertEquals(201, rows.size());
		Row more = rows.get(200);
		assertEquals(Tap.MORE, Tap.of(more.kind(), false));
		assertEquals("200 of 300", more.detail());
		assertEquals(300, reading.more().rows(ROOT, "Show more",
				"%1$d of %2$d").size());
	}

	// The newest of 300 files is listed in the directory's order last;
	// sorted by change, it is the first row, not behind Show more.
	@Test
	public void aSortedLevelIsSortedWholeBeforeItIsPaged() {
		List<Reading.Entry> entries = new ArrayList<>();
		for (int i = 0; i < 300; ++i) {
			entries.add(new Reading.Entry("f" + i + ".frag", false, i));
		}
		entries.add(new Reading.Entry("textures", true, 0));
		Reading reading = Reading.started().listed(entries);
		List<Row> rows = reading.rows(ROOT, "Show more", "%1$d of %2$d",
				Order.CHANGED);
		assertEquals(201, rows.size());
		assertEquals("textures", rows.get(0).name());
		assertEquals("f299.frag", rows.get(1).name());
		assertEquals("f101.frag", rows.get(199).name());
		assertEquals("200 of 301", rows.get(200).detail());
		assertEquals("f0.frag", reading.more().rows(ROOT, "Show more",
				"%1$d of %2$d", Order.CHANGED).get(300).name());
	}

	// Only the rows shown are made, in the order all rows would have.
	@Test
	public void entriesAreOrderedAsTheirRowsAre() {
		List<Reading.Entry> entries = new ArrayList<>();
		for (int i = 0; i < 60; ++i) {
			entries.add(new Reading.Entry((i % 7 == 0 ? "B" : "a") + i +
					".frag", i % 11 == 0, i % 5 == 0 ? 0 : i % 13));
		}
		Reading reading = Reading.started().listed(entries);
		for (Order order : Order.values()) {
			assertEquals(order.sort(reading.rows(ROOT, "Show more",
							"%1$d of %2$d")),
					reading.rows(ROOT, "Show more", "%1$d of %2$d", order));
		}
	}

	// The order the provider lists in never shows: by the setting,
	// folders first, and where that ties, by name.
	@Test
	public void theProvidersOrderNeverShows() {
		List<Reading.Entry> one = List.of(
				new Reading.Entry("many", true, 5, 0),
				new Reading.Entry("big", true, 5, 0),
				new Reading.Entry("b.frag", false, 0, 0),
				new Reading.Entry("A.frag", false, 0, 0),
				new Reading.Entry("images", true, 0, 0));
		List<Reading.Entry> other = new ArrayList<>(one);
		java.util.Collections.reverse(other);
		for (Order order : Order.values()) {
			List<String> names = names(Reading.started().listed(one)
					.rows(ROOT, "Show more", "%1$d of %2$d", order));
			assertEquals(names, names(Reading.started().listed(other)
					.rows(ROOT, "Show more", "%1$d of %2$d", order)));
		}
		assertEquals(List.of("big", "many", "images", "A.frag", "b.frag"),
				names(Reading.started().listed(one).rows(ROOT, "Show more",
						"%1$d of %2$d", Order.CHANGED)));
		assertEquals(List.of("big", "images", "many", "A.frag", "b.frag"),
				names(Reading.started().listed(one).rows(ROOT, "Show more",
						"%1$d of %2$d", Order.BY_NAME)));
	}

	private static List<String> names(List<Row> rows) {
		List<String> names = new ArrayList<>();
		for (Row row : rows) {
			names.add(row.name());
		}
		return names;
	}

	@Test
	public void stopIsOfferedOnlyAfterReadingTakesLong() {
		assertEquals(List.of(), Reading.started().notice().actions());
		assertEquals(List.of(Notice.Action.STOP),
				Reading.started().slow().notice().actions());
	}

	@Test
	public void aResultAfterStoppingChangesNothing() {
		Reading stopped = Reading.started().slow().stopped();
		assertEquals(stopped, stopped.listed(List.of(
				new Reading.Entry("main.frag", false))));
		assertEquals(List.of(Notice.Action.TRY_AGAIN),
				stopped.notice().actions());
	}

	@Test
	public void aFailureSaysWhyAndOffersToTryAgain() {
		Notice notice = Reading.started().failed("I/O error").notice();
		assertEquals(Notice.Kind.FAILED, notice.kind());
		assertEquals("I/O error", notice.reason());
		assertEquals(List.of(Notice.Action.TRY_AGAIN), notice.actions());
	}

	@Test
	public void aRevokedGrantOrGoneStorageAsksToChooseAgain() {
		Notice revoked = Reading.started().failed("lost access", true,
				false, false).notice();
		assertEquals(Notice.Kind.LOST_FOLDER, revoked.kind());
		assertEquals(List.of(Notice.Action.CHOOSE_AGAIN,
				Notice.Action.REMOVE), revoked.actions());
		assertEquals(Notice.Kind.LOST_FOLDER, Reading.started().failed(
				"gone", false, true, false).notice().kind());
		assertEquals(Notice.Kind.LOST_FILE, Reading.started().failed(
				"gone", false, true, true).notice().kind());
	}

	@Test
	public void aFailureWithTheGrantHeldOffersToTryAgain() {
		Reading failed = Reading.started().failed("I/O error", true, true,
				false);
		assertFalse(failed.isLost());
		assertEquals(Notice.Kind.FAILED, failed.notice().kind());
	}

	@Test
	public void anEmptyFolderSaysSoAndAFullOneSaysNothing() {
		assertEquals(Notice.Kind.EMPTY, listed(
				new Reading.Entry(".hidden", false)).notice().kind());
		assertNull(listed(new Reading.Entry("a.glsl", false)).notice());
	}

	@Test
	public void theIconOfAFileIsAHintFromItsName() {
		List<Row> rows = listed(new Reading.Entry("photo.JPG", false),
				new Reading.Entry("main.frag", false),
				new Reading.Entry("notes", false),
				new Reading.Entry("lib.glsl", false))
				.rows(ROOT, "Show more", "%1$d of %2$d");

		// By name alone: a shader named photo.JPG would show as a
		// picture here and still open as text.
		java.util.Map<String, Row.Kind> kinds = new java.util.HashMap<>();
		for (Row row : rows) {
			kinds.put(row.name(), row.kind());
		}
		assertEquals(Row.Kind.IMAGE, kinds.get("photo.JPG"));
		assertEquals(Row.Kind.TEXT, kinds.get("main.frag"));
		assertEquals(Row.Kind.TEXT, kinds.get("notes"));
		assertEquals(Row.Kind.TEXT, kinds.get("lib.glsl"));
	}

	// A file larger than the editor opens, by the size its listing
	// told, cannot be opened here: a tap says why.
	@Test
	public void aFileTooLargeCannotBeOpened() {
		List<Row> rows = listed(
				new Reading.Entry("data.bin", false, 0, Face.LARGEST + 1),
				new Reading.Entry("huge.png", false, 0, Face.LARGEST + 1),
				new Reading.Entry("main.frag", false, 0, Face.LARGEST))
				.rows(ROOT, "Show more", "%1$d of %2$d");
		assertEquals(Row.Kind.OTHER, rows.get(0).kind());
		assertEquals(Row.Kind.OTHER, rows.get(1).kind());
		assertEquals(Row.Kind.TEXT, rows.get(2).kind());
		assertEquals(Tap.SAY_WHY, Tap.of(rows.get(0).kind(), false));
		assertTrue(rows.get(0).picture().dim());
	}

	private static Reading listed(Reading.Entry... entries) {
		return Reading.started().listed(List.of(entries));
	}
}
