package de.markusfisch.android.shadereditor.project;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;
/**
 * A move that cannot be noted, and the examples' store (from an
 * independent review's probes).
 */
public class PictureStoreUnnotedTest {
	@Rule public TemporaryFolder temp = new TemporaryFolder();
	private static ProjectPath path(String s) { return ProjectPath.parse(s).value(); }
	@Test public void unnotableMoveDoesNotShowOldPictureForReusedName() throws Exception {
		File folder = temp.newFolder();
		PictureStore store = new PictureStore(folder, StateFile.Gate.OPEN, null, List.of());
		assertNull(store.put(path("a.frag"), "a", new byte[]{1}));
		FileTexts.Kept kept = store.kept();
		File blocker = new File(folder, "following");
		assertTrue(blocker.mkdir());
		Files.write(new File(blocker,"block").toPath(), new byte[]{1});
		assertFalse(kept.follow(new Journal.Move(path("a.frag"), path("b.frag"))));
		assertNull("new a.frag must not inherit the removed a.frag picture", store.previews().get(path("a.frag")));
	}
	@Test public void examplesStoreAcceptsCaptureWithoutTouchingAssets() throws Exception {
		ProjectSessions sessions = new ProjectSessions(temp.newFolder());
		String id = sessions.open(new ProjectSessions.Locator(ProjectSessions.Kind.EXAMPLES, "examples"));
		PictureStore store = sessions.pictures(id);
		assertNull(store.put(path("a.frag"), PictureStore.BUNDLED, new byte[]{1}));
		assertNull(store.put(path("a.frag"), "capture", new byte[]{2}));
		assertArrayEquals(new byte[]{2}, store.previews().get(path("a.frag")).png());
	}
}
