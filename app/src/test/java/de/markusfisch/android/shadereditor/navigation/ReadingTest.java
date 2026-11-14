package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

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
		assertNull(Level.LIBRARY.key());
		assertEquals(inner, Level.of(inner.name()));
		assertEquals(Level.EXAMPLES, Level.of(Level.EXAMPLES.name()));
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

	private static Reading listed(Reading.Entry... entries) {
		return Reading.started().listed(List.of(entries));
	}
}
