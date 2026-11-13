package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

public class ProjectSessionsTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File root;
	private File project;
	private ProjectSessions sessions;

	@Before
	public void setUp() throws IOException {
		root = new File(temp.getRoot(), "sessions");
		project = temp.newFolder("project");
		sessions = new ProjectSessions(root);
	}

	@Test
	public void keepsLocatorsUnderShortIds() {
		List<Locator> locators = List.of(
				new Locator(Kind.DIRECTORY, project.getPath()),
				new Locator(Kind.TREE, "content://provider/tree/" +
						"primary%3AShaders%2Fcafé"),
				new Locator(Kind.DOCUMENT,
						"content://provider/document/42"),
				new Locator(Kind.DATABASE, "17"));
		for (Locator locator : locators) {
			String id = sessions.open(locator);
			assertNotNull(id);
			assertTrue(id, id.matches("[0-9a-z]{8}"));
			assertEquals(id, sessions.open(locator));

			ProjectSessions restarted = new ProjectSessions(root);
			assertEquals(id, restarted.find(locator));
			assertEquals(locator, restarted.locator(id));
		}
		assertEquals(4, Set.of(root.list()).size());
		assertNotEquals(sessions.open(new Locator(Kind.DATABASE, "17")),
				sessions.open(new Locator(Kind.DOCUMENT, "17")));
	}

	@Test
	public void knowsNothingOfUnknownIds() throws IOException {
		assertNull(sessions.find(new Locator(Kind.DATABASE, "1")));
		assertNull(sessions.locator("00000000"));
		assertNull(sessions.locator("../project"));
		assertNull(sessions.locator(null));
		assertNull(sessions.drafts("00000000"));
		assertNull(sessions.drafts("../sessions"));
		assertTrue(sessions.remove("../project"));
		assertTrue(project.isDirectory());
		assertTrue(sessions.remove("00000000"));

		assertTrue(new File(root, "abcdefgh").mkdirs());
		Files.write(new File(root, "abcdefgh/locator").toPath(),
				new byte[]{1, 2, 3});
		assertNull(sessions.locator("abcdefgh"));
		assertNull(sessions.find(new Locator(Kind.DATABASE, "1")));
	}

	@Test
	public void removingSessionKeepsProjectFiles() throws IOException {
		write(new File(project, "main.glsl"), "stored");
		write(new File(project, "lib.glsl"), "library");
		Locator locator = new Locator(Kind.DIRECTORY, project.getPath());
		String id = sessions.open(locator);
		DirectoryProjectFiles store = new DirectoryProjectFiles(
				new File(sessions.locator(id).value()));
		EditingSession session = EditingSession.restore(store,
				sessions.drafts(id));
		session.setDraft(path("main.glsl"), "saved", revision(store,
				"main.glsl"));
		session.save();
		session.setDraft(path("lib.glsl"), "unsaved", revision(store,
				"lib.glsl"));
		assertFalse(sessions.drafts(id).drafts().isEmpty());

		assertTrue(sessions.discard(id));

		assertNull(sessions.locator(id));
		assertNull(sessions.find(locator));
		assertFalse(new File(root, id).exists());
		assertEquals("saved", read(new File(project, "main.glsl")));
		assertEquals("library", read(new File(project, "lib.glsl")));
		String again = sessions.open(locator);
		assertTrue(sessions.drafts(again).drafts().isEmpty());
	}

	@Test
	public void removingSessionDoesNotFollowLinks() throws IOException {
		write(new File(project, "main.glsl"), "stored");
		String id = sessions.open(new Locator(Kind.DIRECTORY,
				project.getPath()));
		File drafts = new File(root, id + "/drafts");
		assertTrue(drafts.mkdirs());
		Files.createSymbolicLink(new File(drafts, "link").toPath(),
				project.toPath());

		assertTrue(sessions.discard(id));

		assertFalse(new File(root, id).exists());
		assertEquals("stored", read(new File(project, "main.glsl")));
	}

	@Test
	public void removingARecentProjectKeepsItsDrafts() {
		Locator locator = new Locator(Kind.DIRECTORY, project.getPath());
		String id = sessions.open(locator);
		assertTrue(sessions.remember(id, "project", "Shaders", 10));
		assertTrue(sessions.setLast(id));
		sessions.drafts(id).put(path("main.glsl"),
				new DraftFiles.Draft("unsaved", "r0"));

		assertTrue(sessions.remove(id));

		assertNull(sessions.recent(id));
		assertEquals(List.of(), sessions.recent());
		assertNull(sessions.last());
		assertEquals(id, sessions.find(locator));
		assertEquals(Set.of(path("main.glsl")), sessions.unsaved(id));
		assertEquals("unsaved", sessions.drafts(id).drafts()
				.get(path("main.glsl")).text().toString());
		assertTrue(sessions.remember(id, "project", "Shaders", 20));
		assertEquals(List.of(id), ids(sessions.recent()));
	}

	@Test
	public void listsRecentProjectsAcrossRestartsUntilRemoved()
			throws IOException {
		write(new File(project, "main.glsl"), "stored");
		File other = temp.newFolder("other");
		String first = sessions.open(new Locator(Kind.DIRECTORY,
				project.getPath()));
		String second = sessions.open(new Locator(Kind.TREE,
				"content://provider/tree/other"));
		String unlisted = sessions.open(new Locator(Kind.DIRECTORY,
				other.getPath()));
		assertTrue(sessions.remember(first, "project",
				"ShaderEditor / Shaders", 10));
		assertTrue(sessions.remember(second, "other", "Downloads", 20));
		assertFalse(sessions.touch(unlisted, 30));

		ProjectSessions restarted = new ProjectSessions(root);

		assertEquals(List.of(second, first), ids(restarted.recent()));
		assertEquals(new ProjectSessions.Recent(first,
						new Locator(Kind.DIRECTORY, project.getPath()), "project",
						"ShaderEditor / Shaders", 10, false),
				restarted.recent(first));
		restarted.drafts(second).put(path("main.glsl"),
				new DraftFiles.Draft("edited", null));
		assertTrue(restarted.recent(second).unsaved());
		assertNull(restarted.recent(unlisted));

		assertTrue(restarted.touch(first, 40));

		assertEquals(List.of(first, second), ids(restarted.recent()));
		assertEquals("ShaderEditor / Shaders",
				restarted.recent(first).location());

		assertTrue(restarted.remove(first));

		assertEquals(List.of(second), ids(new ProjectSessions(root).recent()));
		assertEquals("stored", read(new File(project, "main.glsl")));
		String again = restarted.open(new Locator(Kind.DIRECTORY,
				project.getPath()));
		assertNull(restarted.recent(again));
	}

	@Test
	public void tellsAboutAProjectWithoutOpeningIt() {
		String id = sessions.open(new Locator(Kind.DIRECTORY,
				project.getPath()));
		assertNull(sessions.shown(id));
		assertEquals(Set.of(), sessions.unsaved(id));

		assertTrue(sessions.setShown(id, path("lib/common.glsl")));
		sessions.drafts(id).put(path("main.glsl"),
				new DraftFiles.Draft("edited", "r0"));
		sessions.drafts(id).put(path("lib/common.glsl"),
				new DraftFiles.Draft("edited", null));
		ProjectSessions restarted = new ProjectSessions(root);

		assertEquals(path("lib/common.glsl"), restarted.shown(id));
		assertEquals(Set.of(path("main.glsl"), path("lib/common.glsl")),
				restarted.unsaved(id));
		assertTrue(restarted.setShown(id, null));
		assertNull(restarted.shown(id));
		assertEquals(Set.of(), restarted.unsaved("unknown1"));
	}

	@Test
	public void remembersTheSessionOpenLast() {
		String id = sessions.open(new Locator(Kind.DIRECTORY,
				project.getPath()));
		assertNull(sessions.last());
		assertFalse(sessions.setLast("unknown1"));

		assertTrue(sessions.setLast(id));

		assertEquals(id, new ProjectSessions(root).last());
		assertTrue(sessions.setLast(null));
		assertNull(sessions.last());
		assertTrue(sessions.setLast(id));
		assertTrue(sessions.remove(id));
		assertNull(sessions.last());
		assertEquals(List.of(), sessions.recent());
	}

	private static List<String> ids(List<ProjectSessions.Recent> recent) {
		List<String> ids = new ArrayList<>();
		for (ProjectSessions.Recent session : recent) {
			ids.add(session.id());
		}
		return ids;
	}

	private static String revision(ProjectFiles store, String path) {
		return ((ProjectFiles.Content) store.read(path(path))).revision();
	}

	private static void write(File file, String text) throws IOException {
		Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
	}

	private static String read(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()),
				StandardCharsets.UTF_8);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
