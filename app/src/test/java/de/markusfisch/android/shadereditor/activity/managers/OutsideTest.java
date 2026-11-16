package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.markusfisch.android.shadereditor.activity.managers.ProjectFile.Outside;
import de.markusfisch.android.shadereditor.activity.managers.ProjectFile.Outside.Kind;
import de.markusfisch.android.shadereditor.project.FileTexts.Found;

/** The row of the bar for each state of a file's storage. */
public class OutsideTest {
	@Test
	public void everyStateOfStorageHasItsRow() {
		assertNull(Outside.of(Found.SAME, null, false, false, null, false));
		assertNull(Outside.of(Found.SAME, null, false, false, null, true));
		assertEquals(new Outside(Kind.CHANGED, "r2"), Outside.of(
				Found.CHANGED, null, false, true, "r2", true));
		assertEquals(new Outside(Kind.DELETED, null), Outside.of(
				Found.MISSING, null, false, true, null, true));
		assertEquals(new Outside(Kind.GONE, null), Outside.of(
				Found.MISSING, null, false, false, null, false));
		// Kept over no file, or typed after the app deleted it: unsaved
		// text of no file, which a save creates.
		assertNull(Outside.of(Found.MISSING, null, false, false, null,
				true));
	}

	@Test
	public void whatCannotBeReadIsNeverOfferedForReloadOrKeep() {
		// Not text or too large now, with unsaved text here: storage
		// counts it as changed, but there is nothing to reload, and
		// keeping would consent to write over a picture.
		for (Found found : new Found[]{Found.UNAVAILABLE, Found.NOT_TEXT,
				Found.TOO_LARGE}) {
			for (boolean modified : new boolean[]{false, true}) {
				assertEquals(new Outside(Kind.UNREADABLE, null), Outside.of(
						found, null, false, modified, "r2", modified));
			}
		}
	}

	@Test
	public void aJournalThatMayBeWholeIsReadAgainNotDiscarded() {
		assertEquals(Kind.JOURNAL, Outside.of(Found.SAME, "damaged", true,
				false, null, false).kind());
		assertEquals(Kind.JOURNAL_UNREAD, Outside.of(Found.SAME,
				"too large for this device", false, false, null, false)
				.kind());
		// Before anything else that holds for the file.
		assertEquals(Kind.JOURNAL, Outside.of(Found.MISSING, "damaged",
				true, false, null, false).kind());
	}
}
