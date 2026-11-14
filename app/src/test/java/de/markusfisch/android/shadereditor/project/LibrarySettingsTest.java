package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class LibrarySettingsTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	@Test
	public void keepsQualityByPathAndOneByDefault() {
		LibrarySettings settings = settings();
		assertEquals(1f, settings.quality(path("Plasma.frag")), 0f);
		assertTrue(settings.setQuality(path("Plasma.frag"), .25f));
		assertTrue(settings.setQuality(path("a b/Clouds 2.frag"), .5f));
		LibrarySettings again = settings();
		assertEquals(.25f, again.quality(path("Plasma.frag")), 0f);
		assertEquals(.5f, again.quality(path("a b/Clouds 2.frag")), 0f);
		assertTrue(again.setQuality(path("Plasma.frag"), 1f));
		assertEquals(1f, settings().quality(path("Plasma.frag")), 0f);
	}

	@Test
	public void keepsTheShaderNewOnesAreCopiedFrom() {
		LibrarySettings settings = settings();
		assertNull(settings.template());
		assertTrue(settings.setTemplate(path("Base.frag")));
		assertTrue(settings.setQuality(path("Base.frag"), .5f));
		assertEquals(path("Base.frag"), settings().template());
		assertTrue(settings().setTemplate(null));
		assertNull(settings().template());
		assertEquals(.5f, settings().quality(path("Base.frag")), 0f);
	}

	@Test
	public void followsARenamedFileAndFolder() {
		LibrarySettings settings = settings();
		settings.setTemplate(path("Base.frag"));
		settings.setQuality(path("old/Base.frag"), .5f);
		settings.setQuality(path("older.frag"), .25f);
		assertTrue(settings.follow(new Journal.Move(path("old"), path("new"))));
		// The template is a file of the root, and follows there.
		assertEquals(path("Base.frag"), settings.template());
		assertTrue(settings.follow(new Journal.Move(path("Base.frag"),
				path("Top.frag"))));
		assertEquals(path("Top.frag"), settings.template());
		assertEquals(.5f, settings.quality(path("new/Base.frag")), 0f);
		assertEquals(1f, settings.quality(path("old/Base.frag")), 0f);
		// A name that only starts alike is another file.
		assertEquals(.25f, settings.quality(path("older.frag")), 0f);
		assertTrue(settings.follow(new Journal.Move(path("older.frag"), path("x.frag"))));
		assertEquals(.25f, settings.quality(path("x.frag")), 0f);
	}

	@Test
	public void forgetsADeletedFileAndFolder() {
		LibrarySettings settings = settings();
		settings.setTemplate(path("Base.frag"));
		settings.setQuality(path("gone/Base.frag"), .5f);
		settings.setQuality(path("stays.frag"), .25f);
		assertTrue(settings.follow(new Journal.Move(path("gone"), null)));
		assertEquals(path("Base.frag"), settings.template());
		assertTrue(settings.follow(new Journal.Move(path("Base.frag"), null)));
		assertNull(settings.template());
		assertEquals(1f, settings.quality(path("gone/Base.frag")), 0f);
		assertEquals(.25f, settings.quality(path("stays.frag")), 0f);
	}

	@Test
	public void aFileNothingWroteIsAsIfThereWereNone() throws IOException {
		File file = new File(temp.getRoot(), "library/settings");
		assertTrue(file.getParentFile().mkdirs());
		try (FileOutputStream out = new FileOutputStream(file)) {
			out.write("quality\tfast\tA.frag\n\u0000\nnonsense".getBytes());
		}
		LibrarySettings settings = settings();
		assertEquals(1f, settings.quality(path("A.frag")), 0f);
		assertNull(settings.template());
		assertTrue(settings.setQuality(path("A.frag"), .5f));
		assertEquals(.5f, settings().quality(path("A.frag")), 0f);
	}

	// Unreadable is not empty: a change would write over what the
	// file holds with less.
	@Test
	public void settingsThatCannotBeReadAreNotWrittenOver()
			throws IOException {
		assertTrue(settings().setQuality(path("Alpha.frag"), .25f));
		assertTrue(settings().setTemplate(path("Alpha.frag")));
		File file = new File(temp.getRoot(), "library/settings");
		assertTrue(file.setReadable(false, false));
		try {
			assertFalse(file.canRead());
			LibrarySettings settings = settings();
			assertFalse(settings.setQuality(path("Beta.frag"), .5f));
			assertFalse(settings.setTemplate(null));
			assertFalse(settings.follow(new Journal.Move(
					path("Alpha.frag"), null)));
			// To show them, the defaults will do.
			assertEquals(1f, settings.quality(path("Alpha.frag")), 0f);
			assertNull(settings.template());
		} finally {
			file.setReadable(true);
		}
		assertEquals(.25f, settings().quality(path("Alpha.frag")), 0f);
		assertEquals(path("Alpha.frag"), settings().template());
		assertEquals(1f, settings().quality(path("Beta.frag")), 0f);
	}

	// What was left at the target, of a file that went without the
	// app knowing, does not win over what came with the renamed file.
	@Test
	public void whatFollowedARenameWinsOverWhatWasLeftAtItsTarget() {
		LibrarySettings settings = settings();
		assertTrue(settings.setQuality(path("Alpha.frag"), .25f));
		assertTrue(settings.setQuality(path("Beta.frag"), .5f));
		assertTrue(settings.follow(new Journal.Move(path("Alpha.frag"),
				path("Beta.frag"))));
		assertEquals(.25f, settings().quality(path("Beta.frag")), 0f);
		assertEquals(1f, settings().quality(path("Alpha.frag")), 0f);
	}

	// Nothing stored is changed for a reader while a rename has not
	// been followed: it is told where to look, and where not.
	@Test
	public void whileARenameIsNotFollowedReadersAreToldWhereThingsAre()
			throws IOException {
		Following following = new Following(new File(temp.getRoot(),
				"library/following"));
		LibrarySettings settings = new LibrarySettings(new File(
				temp.getRoot(), "library/settings"), following,
				StateFile.Gate.OPEN);
		assertTrue(settings.setQuality(path("a/One.frag"), .25f));
		assertTrue(settings.setTemplate(path("Two.frag")));
		assertTrue(following.add(new Journal.Move(path("a"), path("b"))));
		assertTrue(following.add(new Journal.Move(path("Two.frag"),
				path("Three.frag"))));
		assertTrue(following.add(new Journal.Move(path("b/One.frag"),
				path("b/Uno.frag"))));

		assertEquals(.25f, settings.quality(path("b/Uno.frag")), 0f);
		assertEquals(1f, settings.quality(path("b/One.frag")), 0f);
		assertEquals(1f, settings.quality(path("a/One.frag")), 0f);
		assertEquals(path("Three.frag"), settings.template());
		// Deleted, nothing is kept for it, and a later file of the
		// name has nothing.
		assertTrue(following.add(new Journal.Move(path("Three.frag"),
				null)));
		assertNull(settings.template());
		// The note is read again by the next start.
		assertEquals(4, new Following(new File(temp.getRoot(),
				"library/following")).read().size());
	}

	private LibrarySettings settings() {
		return new LibrarySettings(new File(temp.getRoot(),
				"library/settings"));
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
