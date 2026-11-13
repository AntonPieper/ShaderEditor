package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Entries;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Entry;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Failed;

public class SafProjectFilesTest {
	// An e with acute accent, composed and decomposed.
	private static final String COMPOSED = "caf\u00e9.glsl";
	private static final String DECOMPOSED = "cafe\u0301.glsl";

	private final FakeDocuments documents = new FakeDocuments();
	private final SafProjectFiles files = new SafProjectFiles(documents,
			documents.root.id);

	@Test
	public void readsContentAbsentAndFailed() {
		documents.put("main.glsl", "void main() {}");
		documents.folder("folder");
		Content content = (Content) files.read(path("main.glsl"));
		assertEquals("void main() {}", text(content.bytes()));
		assertTrue(content.revision().startsWith("sha256:"));
		assertNull(files.read(path("missing.glsl")));
		assertNull(files.read(path("missing/main.glsl")));
		assertNull(files.read(path("main.glsl/main.glsl")));
		assertEquals(new Failed("it is a folder"), files.read(path("folder")));
	}

	@Test
	public void refusesAFileOverTheLimitAsked() {
		documents.put("main.glsl", "void main() {}");

		assertEquals(new Failed(ProjectFiles.TOO_LARGE),
				files.read(path("main.glsl"), 13));
		assertEquals("void main() {}", text(((Content) files.read(
				path("main.glsl"), 14)).bytes()));
	}

	@Test
	public void doesNotOverwriteAFileTooLargeToHaveBeenRead() {
		documents.put("big.glsl", new String(new char[(int) de.markusfisch
				.android.shadereditor.editor.Document.MAX_BYTES + 1]));
		SafProjectFiles writable = new SafProjectFiles(documents,
				documents.root.id, true);

		ProjectFiles.Change change = writable.write(path("big.glsl"),
				new byte[]{'x'}, "sha256:any");

		assertEquals(ProjectFiles.Refusal.CONFLICT,
				((ProjectFiles.Refused) change).refusal());
	}

	@Test
	public void openingReadsNothingAndAPathReadsOnlyItsWay() {
		for (int i = 0; i < 100; ++i) {
			documents.put("big/file" + i + ".glsl", "");
		}
		FakeDocuments.Node main = documents.put("a/b/main.glsl", "main");
		String a = documents.folder("a").id;
		String b = documents.folder("a/b").id;
		documents.calls.clear();
		SafProjectFiles opened = new SafProjectFiles(documents,
				documents.root.id);
		assertEquals(List.of(), documents.calls);
		assertEquals("main", read(opened, "a/b/main.glsl"));
		assertEquals(List.of("children " + documents.root.id,
				"children " + a, "children " + b, "read " + main.id),
				documents.calls);
	}

	@Test
	public void openingAListedFileIsOneCall() {
		FakeDocuments.Node main = documents.put("a/b/main.glsl", "main");
		FakeDocuments.Node c = documents.folder("a/b/c");
		files.list(null);
		files.list(path("a"));
		files.list(path("a/b"));
		documents.calls.clear();

		assertEquals("main", read(files, "a/b/main.glsl"));
		files.list(path("a/b/c"));

		assertEquals(List.of("read " + main.id, "children " + c.id),
				documents.calls);
	}

	@Test
	public void findsAFileReplacedSinceTheListing() {
		FakeDocuments.Node old = documents.put("lib/common.glsl", "old");
		files.list(path("lib"));
		documents.nodes.remove(old.id);
		documents.put("lib/common.glsl", "new");
		documents.put("lib/added.glsl", "added");

		assertEquals("new", read(files, "lib/common.glsl"));
		assertEquals("added", read(files, "lib/added.glsl"));
	}

	@Test
	public void forgettingListingsFindsRenamesMadeElsewhere() {
		FakeDocuments.Node main = documents.put("main.glsl", "main");
		files.list(null);
		main.name = "renamed.glsl";

		files.forgetListings();

		assertNull(files.read(path("main.glsl")));
		assertEquals("main", read(files, "renamed.glsl"));
	}

	@Test
	public void listingAFolderAgainForgetsFoldersReplacedInIt() {
		FakeDocuments.Node old = documents.put("lib/sub/a.glsl", "old");
		files.list(path("lib/sub"));
		old.parent.parent.name = "moved";
		documents.put("lib/sub/a.glsl", "new");
		documents.put("kept/b.glsl", "kept");
		files.list(path("kept"));

		files.list(null);

		assertEquals("new", read(files, "lib/sub/a.glsl"));
		documents.calls.clear();
		assertEquals("kept", read(files, "kept/b.glsl"));
		assertEquals(1, documents.calls.size());
	}

	@Test
	public void revisionChangesWithTheBytes() {
		documents.put("a.glsl", "same");
		documents.put("b.glsl", "same");
		FakeDocuments.Node c = documents.put("c.glsl", "other");
		String revision = revision("a.glsl");
		assertEquals(revision, revision("b.glsl"));
		assertNotEquals(revision, revision("c.glsl"));
		c.bytes = "same".getBytes(StandardCharsets.UTF_8);
		assertEquals(revision, revision("c.glsl"));
	}

	@Test
	public void listsEntriesWithVersionControlFolders() {
		documents.put("main.glsl", "");
		documents.put("assets/noise.png", "");
		documents.put(".git/config", "");
		documents.put(DECOMPOSED, "");
		assertEquals(Set.of("main.glsl", "assets/", ".git/", COMPOSED),
				names(null));
		assertEquals(Set.of("noise.png"), names("assets"));
		assertNull(files.list(path("missing")));
		assertEquals(new Failed("it is not a folder"),
				files.list(path("main.glsl")));
		assertFalse(ProjectPath.parse(".git/config").isSuccess());
	}

	@Test
	public void listsSizeAndTimeOfLastChangeWithoutReading() {
		documents.put("main.glsl", "void main() {}").modified = 1234;
		documents.put("lib/common.glsl", "");
		documents.calls.clear();

		Entries entries = (Entries) files.list(null);

		assertEquals(Set.of(new Entry("main.glsl", false, 14, 1234),
						new Entry("lib", true)),
				Set.copyOf(entries.entries()));
		assertEquals(List.of("children " + documents.root.id),
				documents.calls);
	}

	@Test
	public void namesDifferingInCaseAreDifferentPaths() {
		documents.put("Main.glsl", "upper");
		assertNull(files.read(path("main.glsl")));
		documents.put("main.glsl", "lower");
		assertEquals("upper", read(files, "Main.glsl"));
		assertEquals("lower", read(files, "main.glsl"));
	}

	@Test
	public void decomposedNamesMatchComposedPaths() {
		documents.put("lib/" + DECOMPOSED, "text");
		assertEquals("text", read(files, "lib/" + COMPOSED));
	}

	@Test
	public void namesThatNormalizeAlikeFailToRead() {
		documents.put(DECOMPOSED, "decomposed");
		documents.put(COMPOSED, "composed");
		documents.put("lib/" + DECOMPOSED + "/a.glsl", "");
		documents.put("lib/" + COMPOSED + "/a.glsl", "");
		assertEquals(new Failed("two entries are named " + COMPOSED),
				files.read(path(COMPOSED)));
		assertEquals(new Failed("two entries are named " + COMPOSED),
				files.read(path("lib/" + COMPOSED + "/a.glsl")));
		assertEquals(3, ((Entries) files.list(null)).entries().size());
	}

	@Test
	public void lostPermissionFails() {
		documents.put("main.glsl", "");
		documents.failure = new SecurityException("revoked");
		Failed lost = new Failed(SafProjectFiles.LOST_FOLDER);
		assertEquals(lost, files.read(path("main.glsl")));
		assertEquals(lost, files.list(null));
		assertEquals(SafProjectFiles.LOST_FOLDER, files.check());
	}

	@Test
	public void vanishedDocumentsFail() {
		FakeDocuments.Node main = documents.put("main.glsl", "");
		documents.nodes.remove(main.id);
		assertNull(files.read(path("main.glsl")));
		documents.nodes.remove(documents.root.id);
		Failed gone = new Failed("it is no longer available");
		assertEquals(gone, files.read(path("main.glsl")));
		assertEquals(gone, files.list(null));
		assertEquals("it is no longer available", files.check());
		documents.failure = new FileNotFoundException("doc1");
		assertEquals(gone, files.read(path("main.glsl")));
	}

	@Test
	public void providerExceptionsFail() {
		documents.failure = new IOException("disk");
		assertEquals(new Failed("I/O error: disk"), files.list(null));
		documents.failure = new IllegalStateException("crashed");
		assertEquals(new Failed("the storage failed: crashed"),
				files.read(path("main.glsl")));
		documents.failure = new UnsupportedOperationException();
		assertEquals(new Failed("the storage failed"), files.list(null));
	}

	@Test
	public void checksTheRoot() {
		assertNull(files.check());
		FakeDocuments.Node file = documents.put("main.glsl", "");
		assertEquals("it is not a folder", new SafProjectFiles(documents,
				file.id).check());
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

	private static String read(ProjectFiles files, String path) {
		return text(((Content) files.read(path(path))).bytes());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	private static String text(byte[] bytes) {
		return new String(bytes, StandardCharsets.UTF_8);
	}
}
