package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.TimeZone;

import de.markusfisch.android.shadereditor.database.DataRecords.ShaderInfo;

public class LibrarySourceTest {
	private static final long HOUR = 3_600_000L;
	// 2025-11-12 00:30 in Berlin, an hour ahead of UTC.
	private static final long NOW = 1_762_903_800_000L;
	private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

	@Test
	public void aShaderWithoutANameIsUntitledNotItsDate() {
		String modified = "2025-11-12 00:15:00";
		assertEquals("Untitled", LibrarySource.name(
				new ShaderInfo(1, null, modified, null), "Untitled"));
		assertEquals("Untitled", LibrarySource.name(
				new ShaderInfo(2, "", modified, null), "Untitled"));
		assertEquals("Horizon", LibrarySource.name(
				new ShaderInfo(3, "Horizon", modified, null), "Untitled"));
	}

	@Test
	public void daysAreCountedByTheLocalCalendar() {
		assertEquals(0, LibrarySource.daysBetween(NOW - HOUR / 4, NOW, BERLIN));
		assertEquals(1, LibrarySource.daysBetween(NOW - HOUR, NOW, BERLIN));
		assertEquals(1, LibrarySource.daysBetween(NOW - 24 * HOUR, NOW, BERLIN));
		assertEquals(2, LibrarySource.daysBetween(NOW - 25 * HOUR, NOW, BERLIN));
	}
}
