package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.project.ProjectFiles.Change;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Done;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Entries;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Entry;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Failed;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refusal;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refused;

public class DirectoryProjectFilesTest {
	// "é" composed and decomposed.
	private static final String COMPOSED = "café.glsl";
	private static final String DECOMPOSED = "café.glsl";

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File root;
	private File outside;
	private DirectoryProjectFiles files;

	@Before
	public void setUp() throws IOException {
		root = temp.newFolder("root");
		outside = temp.newFolder("outside");
		files = new DirectoryProjectFiles(root);
	}

	@Test
	public void readsContentAbsentAndFailed() throws IOException {
		put("main.glsl", "void main() {}");
		assertTrue(new File(root, "folder").mkdir());
		Content content = (Content) files.read(path("main.glsl"));
		assertEquals("void main() {}", text(content.bytes()));
		assertTrue(content.revision().startsWith("sha256:"));
		assertNull(files.read(path("missing.glsl")));
		assertNull(files.read(path("missing/main.glsl")));
		assertNull(files.read(path("main.glsl/main.glsl")));
		assertFailed("it is a folder", files.read(path("folder")));
	}

	@Test
	public void unreadableFileFails() throws IOException {
		File file = put("main.glsl", "void main() {}");
		assertTrue(file.setReadable(false));
		try {
			assertTrue(files.read(path("main.glsl")) instanceof Failed);
		} finally {
			assertTrue(file.setReadable(true));
		}
	}

	@Test
	public void missingRootFails() {
		files = new DirectoryProjectFiles(new File(root, "gone"));
		assertFailed("the project folder is missing",
				files.read(path("main.glsl")));
		assertFailed("the project folder is missing", files.list(null));
		assertRefused(Refusal.FAILED, files.write(path("main.glsl"),
				bytes("x"), null));
	}

	@Test
	public void revisionChangesWithTheBytes() throws IOException {
		put("a.glsl", "same");
		put("b.glsl", "same");
		put("c.glsl", "other");
		String a = revision("a.glsl");
		assertEquals(a, revision("b.glsl"));
		assertNotEquals(a, revision("c.glsl"));
		put("a.glsl", "changed");
		assertNotEquals(a, revision("a.glsl"));
	}

	@Test
	public void listsEntriesWithVersionControlFolders() throws IOException {
		put("main.glsl", "");
		put("assets/noise.png", "");
		put(".git/config", "");
		assertEquals(Set.of("main.glsl", "assets/", ".git/"), names(null));
		assertEquals(Set.of("noise.png"), names("assets"));
		assertNull(files.list(path("missing")));
		assertFailed("it is not a folder", files.list(path("main.glsl")));
		assertFalse(ProjectPath.parse(".git/config").isSuccess());
	}

	@Test
	public void listsSizeAndTimeOfLastChange() throws IOException {
		assertTrue(put("main.glsl", "void main() {}").setLastModified(
				1_700_000_000_000L));
		put("lib/common.glsl", "");

		Entries entries = (Entries) files.list(null);

		assertEquals(Set.of(new Entry("main.glsl", false, 14,
						1_700_000_000_000L),
				new Entry("lib", true, ProjectFiles.UNKNOWN,
						new File(root, "lib").lastModified())),
				Set.copyOf(entries.entries()));
	}

	@Test
	public void writesFilesAndMissingFolders() throws IOException {
		Change change = files.write(path("passes/blur.glsl"), bytes("x"),
				null);
		assertEquals(new Done(revision("passes/blur.glsl")), change);
		assertEquals("x", text(Files.readAllBytes(
				new File(root, "passes/blur.glsl").toPath())));

		change = files.write(path("passes/blur.glsl"), bytes("y"),
				((Done) change).revision());
		assertEquals(new Done(revision("passes/blur.glsl")), change);
		assertEquals("y", read("passes/blur.glsl"));
		assertEquals(Set.of("blur.glsl"), onDisk("passes"));
	}

	@Test
	public void staleRevisionIsAConflictThatChangesNothing()
			throws IOException {
		put("main.glsl", "old");
		String seen = revision("main.glsl");
		put("main.glsl", "external");
		assertRefused(Refusal.CONFLICT, files.write(path("main.glsl"),
				bytes("mine"), seen));
		assertRefused(Refusal.CONFLICT, files.write(path("main.glsl"),
				bytes("mine"), null));
		assertEquals("external", read("main.glsl"));

		assertTrue(new File(root, "main.glsl").delete());
		assertRefused(Refusal.CONFLICT, files.write(path("main.glsl"),
				bytes("mine"), seen));
		assertRefused(Refusal.CONFLICT, files.write(path("new/a.glsl"),
				bytes("mine"), seen));
		assertEquals(Set.of(), onDisk(""));
	}

	@Test
	public void refusesWritesOverAndBelowNonFolders() throws IOException {
		put("folder/a.glsl", "");
		put("file.glsl", "keep");
		assertRefused(Refusal.FAILED, files.write(path("folder"),
				bytes("x"), null));
		assertRefused(Refusal.FAILED, files.write(path("file.glsl/a.glsl"),
				bytes("x"), null));
		assertRefused(Refusal.FAILED, files.createFolder(
				path("file.glsl/sub")));
		assertEquals("keep", read("file.glsl"));
		assertEquals(Set.of("folder", "file.glsl"), onDisk(""));
	}

	@Test
	public void createsFolders() {
		assertEquals(new Done(null), files.createFolder(path("a/b")));
		assertTrue(new File(root, "a/b").isDirectory());
		assertRefused(Refusal.EXISTS, files.createFolder(path("a/b")));
		assertRefused(Refusal.EXISTS, files.createFolder(path("a")));
	}

	@Test
	public void renamesFilesAndFolders() throws IOException {
		put("a.glsl", "a");
		put("b.glsl", "b");
		put("lib/x.glsl", "x");
		assertEquals(new Done(null), files.rename(path("a.glsl"),
				path("moved/c.glsl")));
		assertEquals("a", read("moved/c.glsl"));
		assertNull(files.read(path("a.glsl")));

		assertEquals(new Done(null), files.rename(path("lib"),
				path("include")));
		assertEquals("x", read("include/x.glsl"));

		assertRefused(Refusal.EXISTS, files.rename(path("b.glsl"),
				path("moved/c.glsl")));
		assertRefused(Refusal.ABSENT, files.rename(path("a.glsl"),
				path("d.glsl")));
		assertRefused(Refusal.FAILED, files.rename(path("include"),
				path("include/inner")));
		assertEquals("b", read("b.glsl"));
		assertEquals("a", read("moved/c.glsl"));
		assertEquals(Set.of("x.glsl"), onDisk("include"));
	}

	@Test
	public void renamesToADifferentCase() throws IOException {
		put("main.glsl", "m");
		assertEquals(new Done(null), files.rename(path("main.glsl"),
				path("Main.glsl")));
		assertEquals(Set.of("Main.glsl"), onDisk(""));
		assertEquals("m", read("Main.glsl"));
	}

	@Test
	public void deletesFilesAndFolders() throws IOException {
		put("a.glsl", "");
		put("lib/sub/x.glsl", "");
		File kept = put(outside, "kept.glsl", "kept");
		Files.createSymbolicLink(new File(root, "lib/sub/link").toPath(),
				outside.toPath());
		assertEquals(new Done(null), files.delete(path("a.glsl")));
		assertEquals(new Done(null), files.delete(path("lib")));
		assertEquals(Set.of(), onDisk(""));
		assertEquals("kept", text(Files.readAllBytes(kept.toPath())));
		assertRefused(Refusal.ABSENT, files.delete(path("a.glsl")));
	}

	@Test
	public void namesDifferingInCaseAreDifferentPaths() throws IOException {
		put("Main.glsl", "upper");
		assertNull(files.read(path("main.glsl")));
		assertEquals(Set.of("Main.glsl"), names(null));
		Change change = files.write(path("main.glsl"), bytes("lower"), null);
		if (foldsCase()) {
			assertRefused(Refusal.EXISTS, change);
			assertEquals(Set.of("Main.glsl"), onDisk(""));
			assertRefused(Refusal.EXISTS, files.createFolder(path("MAIN.glsl")));
		} else {
			assertTrue(change instanceof Done);
			assertEquals("lower", read("main.glsl"));
			assertEquals(Set.of("Main.glsl", "main.glsl"), onDisk(""));
		}
		assertEquals("upper", read("Main.glsl"));
	}

	@Test
	public void decomposedNamesMatchComposedPaths() throws IOException {
		put(DECOMPOSED, "text");
		Set<String> stored = onDisk("");
		assertEquals(1, stored.size());
		assertEquals("text", read(COMPOSED));
		assertEquals(Set.of(COMPOSED), names(null));
		assertTrue(files.write(path(COMPOSED), bytes("new"),
				revision(COMPOSED)) instanceof Done);
		assertEquals(stored, onDisk(""));
		assertEquals("new", read(COMPOSED));
	}

	@Test
	public void namesThatNormalizeAlikeFailToRead() throws IOException {
		put(DECOMPOSED, "decomposed");
		put(COMPOSED, "composed");
		if (onDisk("").size() == 2) {
			// The file system keeps both spellings apart.
			assertFailed("two entries are named " + COMPOSED,
					files.read(path(COMPOSED)));
			assertRefused(Refusal.FAILED, files.write(path(COMPOSED),
					bytes("x"), null));
			assertRefused(Refusal.FAILED, files.delete(path(COMPOSED)));
			assertEquals(2, ((Entries) files.list(null)).entries().size());
		} else {
			// The file system takes both for one name.
			assertEquals("composed", read(COMPOSED));
		}
	}

	@Test
	public void refusesLinksInsideTheRoot() throws IOException {
		put("main.glsl", "main");
		put("lib/a.glsl", "a");
		link("alias.glsl", new File(root, "main.glsl"));
		link("folder", new File(root, "lib"));
		assertFailed("alias.glsl is a link", files.read(path("alias.glsl")));
		assertFailed("folder is a link", files.read(path("folder/a.glsl")));
		assertEquals(Set.of("main.glsl", "lib/"), names(null));
		assertRefused(Refusal.FAILED, files.write(path("alias.glsl"),
				bytes("x"), revision("main.glsl")));
		assertRefused(Refusal.FAILED, files.write(path("folder/new.glsl"),
				bytes("x"), null));
		assertEquals("main", read("main.glsl"));
		assertEquals(Set.of("a.glsl"), onDisk("lib"));
	}

	@Test
	public void refusesLinksLeavingTheRoot() throws IOException {
		put("main.glsl", "main");
		put(outside, "secret.glsl", "secret");
		link("secret.glsl", new File(outside, "secret.glsl"));
		link("out", outside);
		link("dangling.glsl", new File(outside, "missing.glsl"));
		assertFailed("secret.glsl is a link", files.read(path("secret.glsl")));
		assertFailed("out is a link", files.read(path("out/secret.glsl")));
		assertTrue(files.list(path("out")) instanceof Failed);
		assertFailed("dangling.glsl is a link",
				files.read(path("dangling.glsl")));
		assertRefused(Refusal.FAILED, files.write(path("out/new.glsl"),
				bytes("x"), null));
		assertRefused(Refusal.FAILED, files.write(path("dangling.glsl"),
				bytes("x"), null));
		assertRefused(Refusal.FAILED, files.createFolder(path("out/new")));
		assertRefused(Refusal.FAILED, files.rename(path("out/secret.glsl"),
				path("stolen.glsl")));
		assertRefused(Refusal.FAILED, files.rename(path("main.glsl"),
				path("out/main.glsl")));
		assertRefused(Refusal.FAILED, files.delete(path("out")));
		assertEquals(Set.of("secret.glsl"), onDisk(outside));
		assertEquals("secret", text(Files.readAllBytes(
				new File(outside, "secret.glsl").toPath())));
	}

	@Test
	public void rootReachedThroughALinkWorks() throws IOException {
		put("main.glsl", "main");
		File alias = new File(temp.getRoot(), "alias");
		Files.createSymbolicLink(alias.toPath(), root.toPath());
		files = new DirectoryProjectFiles(alias);
		assertEquals("main", read("main.glsl"));
		assertTrue(files.write(path("a.glsl"), bytes("a"), null)
				instanceof Done);
		assertEquals(Set.of("main.glsl", "a.glsl"), onDisk(""));
	}

	@Test
	public void nothingAboveTheRootIsReachable() throws IOException {
		put(temp.getRoot(), "above.glsl", "above");
		assertFalse(ProjectPath.parse("../above.glsl").isSuccess());
		assertFalse(ProjectPath.parse("a/../../above.glsl").isSuccess());
		assertNull(files.read(path("above.glsl")));
		assertNull(files.read(path("a/../above.glsl")));
		assertTrue(files.write(path("a/../above.glsl"), bytes("x"), null)
				instanceof Done);
		assertEquals("above", text(Files.readAllBytes(
				new File(temp.getRoot(), "above.glsl").toPath())));
		assertEquals(Set.of("above.glsl"), onDisk(""));
	}

	private boolean foldsCase() throws IOException {
		File probe = temp.newFolder("probe");
		assertTrue(new File(probe, "case").createNewFile());
		return new File(probe, "CASE").exists();
	}

	private String read(String path) {
		return text(((Content) files.read(path(path))).bytes());
	}

	private String revision(String path) {
		return ((Content) files.read(path(path))).revision();
	}

	private Set<String> names(String folder) {
		Set<String> names = new TreeSet<>();
		for (Entry entry : ((Entries) files.list(folder != null
				? path(folder)
				: null)).entries()) {
			names.add(entry.name() + (entry.folder() ? "/" : ""));
		}
		return names;
	}

	private Set<String> onDisk(String folder) {
		return onDisk(new File(root, folder));
	}

	private static Set<String> onDisk(File folder) {
		return new TreeSet<>(Arrays.asList(folder.list()));
	}

	private File put(String path, String text) throws IOException {
		return put(root, path, text);
	}

	private static File put(File dir, String path, String text)
			throws IOException {
		File file = new File(dir, path);
		File parent = file.getParentFile();
		assertTrue(parent.isDirectory() || parent.mkdirs());
		Files.write(file.toPath(), bytes(text));
		return file;
	}

	private void link(String name, File target) throws IOException {
		Files.createSymbolicLink(new File(root, name).toPath(),
				target.toPath());
	}

	private static void assertFailed(String reason, Object result) {
		assertEquals(new Failed(reason), result);
	}

	private static void assertRefused(Refusal refusal, Change change) {
		assertTrue(String.valueOf(change), change instanceof Refused);
		assertEquals(String.valueOf(change), refusal,
				((Refused) change).refusal());
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
