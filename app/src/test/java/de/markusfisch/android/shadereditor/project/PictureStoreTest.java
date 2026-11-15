package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/**
 * Every folder keeps its pictures in a store of one kind: they follow
 * its renames and deletions with the library's guarantees (FILES 5.1a).
 */
public class PictureStoreTest {
	private static final byte[] PNG = {1, 2, 3};

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// A follow that fails is noted, not dropped: until it is done, the
	// picture is answered from where it is still kept, a file made
	// under the old name gets none, and the next follow finishes it.
	@Test
	public void aFailedFollowIsNotedAndNeverDropped() throws IOException {
		ProjectSessions sessions = new ProjectSessions(temp.newFolder(
				"sessions"));
		String id = sessions.open(new Locator(Kind.DIRECTORY,
				temp.newFolder("Sketches").getPath()));
		PictureStore store = sessions.pictures(id);
		assertNull(store.put(path("Aurora.frag"), "r1", PNG));
		File previews = new File(new File(temp.getRoot(), "sessions"),
				id + "/previews");
		FileTexts.Kept kept = store.kept();

		assertTrue(previews.setWritable(false, false));
		try {
			// Noted, though it cannot follow now.
			assertTrue(kept.follow(new Journal.Move(path("Aurora.frag"),
					path("Sky.frag"))));
			assertFalse(store.follow());
			assertArrayEquals(PNG, store.previews().get(path("Sky.frag"))
					.png());
			assertNull(store.previews().get(path("Aurora.frag")));
		} finally {
			assertTrue(previews.setWritable(true, false));
		}

		assertTrue(store.follow());
		assertArrayEquals(PNG, sessions.pictures(id).previews().get(
				path("Sky.frag")).png());
		assertNull(sessions.pictures(id).previews().get(
				path("Aurora.frag")));
	}

	// A picture is not written while a noted move cannot be followed,
	// so none lands under a path that is about to move.
	@Test
	public void nothingIsWrittenWhileAMoveIsNotFollowed()
			throws IOException {
		ProjectSessions sessions = new ProjectSessions(temp.newFolder(
				"sessions"));
		String id = sessions.open(new Locator(Kind.DIRECTORY,
				temp.newFolder("Sketches").getPath()));
		PictureStore store = sessions.pictures(id);
		assertNull(store.put(path("a.frag"), "r1", PNG));
		File previews = new File(new File(temp.getRoot(), "sessions"),
				id + "/previews");
		assertTrue(store.kept().follow(new Journal.Move(path("a.frag"),
				null)));
		assertTrue(previews.setWritable(false, false));
		try {
			// The deletion did not follow, so a new a.frag gets nothing.
			assertNull(store.previews().get(path("a.frag")));
			assertTrue(store.put(path("b.frag"), "r1", PNG) != null);
		} finally {
			assertTrue(previews.setWritable(true, false));
		}
		assertNull(store.put(path("b.frag"), "r1", PNG));
		assertNull(store.previews().get(path("a.frag")));
		assertTrue(List.of(previews.list()).size() == 1);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
