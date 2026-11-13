package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.provider.DocumentsContract;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.project.DocumentResolver.Document;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Change;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Done;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Offers;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refusal;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refused;

public class SafProjectFilesChangeTest {
	// An e with acute accent, composed and decomposed.
	private static final String COMPOSED = "caf\u00e9.glsl";
	private static final String DECOMPOSED = "cafe\u0301.glsl";

	private final FakeDocuments documents = new FakeDocuments();
	private SafProjectFiles files = new SafProjectFiles(documents,
			documents.root.id, true);

	@Test
	public void flagsDecideTheOffers() {
		int all = FakeDocuments.FILE_FLAGS | FakeDocuments.FOLDER_FLAGS;
		assertEquals(new Offers(true, false, true, true, true),
				SafProjectFiles.offers(new Document("f", "f", false, all), true));
		assertEquals(new Offers(false, true, true, true, true),
				SafProjectFiles.offers(new Document("d", "d", true, all), true));
		assertEquals(Offers.NONE,
				SafProjectFiles.offers(new Document("f", "f", false, all), false));
		assertEquals(Offers.NONE, SafProjectFiles.offers(new Document("f", "f", false,
				DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL), true));
		assertEquals(new Offers(true, false, false, false, false),
				SafProjectFiles.offers(new Document("f", "f", false,
						DocumentsContract.Document.FLAG_SUPPORTS_WRITE), true));
		assertFalse(Offers.NONE.any());
	}

	@Test
	public void offersOfPaths() {
		documents.put("main.glsl", "").flags =
				DocumentsContract.Document.FLAG_SUPPORTS_DELETE;
		assertEquals(new Offers(false, false, false, false, true),
				files.offers(path("main.glsl")));
		assertTrue(files.offers(null).create());
		assertEquals(Offers.NONE, files.offers(path("missing.glsl")));
		documents.calls.clear();
		assertTrue(files.offers(path("main.glsl")).delete());
		assertEquals(List.of(), documents.calls);
		assertEquals(Offers.NONE, new SafProjectFiles(documents,
				documents.root.id).offers(null));
		documents.failure = new SecurityException("revoked");
		assertEquals(Offers.NONE, files.offers(null));
	}

	@Test
	public void writesFilesAndMissingFolders() {
		Change change = files.write(path("passes/blur.glsl"), bytes("x"),
				null);
		assertEquals(new Done(revision("passes/blur.glsl")), change);
		assertEquals("x", read("passes/blur.glsl"));

		change = files.write(path("passes/blur.glsl"), bytes("y"),
				((Done) change).revision());
		assertEquals(new Done(revision("passes/blur.glsl")), change);
		assertEquals("y", read("passes/blur.glsl"));
		assertEquals(Set.of("blur.glsl"), stored("passes"));
	}

	@Test
	public void staleRevisionIsAConflictThatChangesNothing() {
		FakeDocuments.Node main = documents.put("main.glsl", "old");
		String seen = revision("main.glsl");
		main.bytes = bytes("external");
		assertRefused(Refusal.CONFLICT, files.write(path("main.glsl"),
				bytes("mine"), seen));
		assertRefused(Refusal.CONFLICT, files.write(path("main.glsl"),
				bytes("mine"), null));
		assertEquals("external", documents.text(main));

		documents.nodes.remove(main.id);
		assertRefused(Refusal.CONFLICT, files.write(path("main.glsl"),
				bytes("mine"), seen));
		assertRefused(Refusal.CONFLICT, files.write(path("new/a.glsl"),
				bytes("mine"), seen));
		assertEquals(Set.of(), stored(""));
	}

	@Test
	public void refusesWritesOverAndBelowNonFolders() {
		documents.put("folder/a.glsl", "");
		FakeDocuments.Node file = documents.put("file.glsl", "keep");
		assertRefused(Refusal.FAILED, files.write(path("folder"),
				bytes("x"), null));
		assertRefused(Refusal.FAILED, files.write(path("file.glsl/a.glsl"),
				bytes("x"), null));
		assertRefused(Refusal.FAILED, files.createFolder(
				path("file.glsl/sub")));
		assertEquals("keep", documents.text(file));
		assertEquals(Set.of("folder", "file.glsl"), stored(""));
	}

	@Test
	public void createsFolders() {
		assertEquals(new Done(null), files.createFolder(path("a/b")));
		assertTrue(documents.folder("a/b").folder);
		assertRefused(Refusal.EXISTS, files.createFolder(path("a/b")));
		assertRefused(Refusal.EXISTS, files.createFolder(path("a")));
	}

	@Test
	public void renamingForgetsTheListings() {
		documents.put("a.glsl", "a");
		files.list(null);

		files.rename(path("a.glsl"), path("b.glsl"));

		assertNull(files.read(path("a.glsl")));
		assertEquals("a", read("b.glsl"));
	}

	@Test
	public void renamesAndMovesFilesAndFolders() {
		documents.put("a.glsl", "a");
		documents.put("b.glsl", "b");
		documents.put("lib/x.glsl", "x");
		assertEquals(new Done(null), files.rename(path("a.glsl"),
				path("moved/c.glsl")));
		assertEquals("a", read("moved/c.glsl"));
		assertNull(files.read(path("a.glsl")));

		assertEquals(new Done(null), files.rename(path("lib"),
				path("include")));
		assertEquals("x", read("include/x.glsl"));
		assertEquals(new Done(null), files.rename(path("b.glsl"),
				path("include/b.glsl")));
		assertEquals("b", read("include/b.glsl"));

		assertRefused(Refusal.EXISTS, files.rename(path("include/b.glsl"),
				path("moved/c.glsl")));
		assertRefused(Refusal.ABSENT, files.rename(path("a.glsl"),
				path("d.glsl")));
		assertRefused(Refusal.FAILED, files.rename(path("include"),
				path("include/inner")));
		assertEquals(Set.of("x.glsl", "b.glsl"), stored("include"));
	}

	@Test
	public void renamesOnlyWhatTheFlagsOffer() {
		documents.put("a.glsl", "a").flags =
				DocumentsContract.Document.FLAG_SUPPORTS_RENAME;
		documents.folder("lib");
		assertRefused(Refusal.UNSUPPORTED, files.rename(path("a.glsl"),
				path("lib/a.glsl")));
		assertEquals(new Done(null), files.rename(path("a.glsl"),
				path("b.glsl")));
		documents.put("c.glsl", "c").flags =
				DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
		assertRefused(Refusal.UNSUPPORTED, files.rename(path("c.glsl"),
				path("d.glsl")));
		assertRefused(Refusal.UNSUPPORTED, files.rename(path("c.glsl"),
				path("lib/d.glsl")));
		assertEquals(new Done(null), files.rename(path("c.glsl"),
				path("lib/c.glsl")));
		assertEquals(Set.of("b.glsl", "lib"), stored(""));
		assertEquals(Set.of("c.glsl"), stored("lib"));
	}

	@Test
	public void deletesFilesAndFolders() {
		documents.put("a.glsl", "");
		documents.put("lib/sub/x.glsl", "");
		assertEquals(new Done(null), files.delete(path("a.glsl")));
		assertEquals(new Done(null), files.delete(path("lib")));
		assertEquals(Set.of(), stored(""));
		assertEquals(1, documents.nodes.size());
		assertRefused(Refusal.ABSENT, files.delete(path("a.glsl")));
		documents.put("kept.glsl", "").flags = 0;
		assertRefused(Refusal.UNSUPPORTED, files.delete(path("kept.glsl")));
	}

	@Test
	public void readOnlyFileInAWritableFolder() {
		FakeDocuments.Node main = documents.put("main.glsl", "old");
		main.flags = 0;
		assertRefused(Refusal.UNSUPPORTED, files.write(path("main.glsl"),
				bytes("new"), revision("main.glsl")));
		assertEquals("old", documents.text(main));
		assertTrue(files.write(path("other.glsl"), bytes("x"), null)
				instanceof Done);
	}

	@Test
	public void readOnlyProviderRefusesEveryChange() {
		documents.folderFlags = 0;
		documents.fileFlags = 0;
		documents.root.flags = 0;
		FakeDocuments.Node main = documents.put("main.glsl", "main");
		String revision = revision("main.glsl");
		documents.calls.clear();
		assertEveryChangeUnsupported(revision);
		assertRefused(Refusal.ABSENT, files.delete(path("absent")));
		assertEquals("main", documents.text(main));
		for (String call : documents.calls) {
			assertTrue(call, call.startsWith("document ") ||
					call.startsWith("children ") || call.startsWith("read "));
		}
	}

	@Test
	public void aFileChangesAsItsFlagsOfferBelowARootThatOffersNothing() {
		documents.root.flags = 0;
		FakeDocuments.Node main = documents.put("main.glsl", "main");
		documents.put("gone.glsl", "gone");
		String revision = revision("main.glsl");

		assertTrue(files.offers(path("main.glsl")).write());
		assertTrue(files.write(path("main.glsl"), bytes("new"), revision)
				instanceof Done);
		assertEquals("new", documents.text(main));
		assertTrue(files.rename(path("main.glsl"), path("renamed.glsl"))
				instanceof Done);
		assertTrue(files.delete(path("gone.glsl")) instanceof Done);
		assertRefused(Refusal.UNSUPPORTED, files.write(path("new.glsl"),
				bytes("x"), null));
		assertRefused(Refusal.UNSUPPORTED, files.createFolder(path("a")));
	}

	@Test
	public void readOnlyGrantRefusesEveryChange() {
		FakeDocuments.Node main = documents.put("main.glsl", "main");
		String revision = revision("main.glsl");
		files = new SafProjectFiles(documents, documents.root.id, false);
		documents.calls.clear();
		assertEveryChangeUnsupported(revision);
		assertRefused(Refusal.UNSUPPORTED, files.delete(path("absent")));
		assertEquals(List.of(), documents.calls);
		assertEquals("main", documents.text(main));
		assertEquals("main", read("main.glsl"));
	}

	@Test
	public void namesTheProviderChangesAreRefused() {
		documents.foldsCase = true;
		documents.put("Main.glsl", "upper");
		Change change = files.write(path("main.glsl"), bytes("lower"), null);
		assertRefused(Refusal.FAILED, change);
		assertEquals("the storage stored main.glsl as main.glsl (1)",
				((Refused) change).reason());
		assertRefused(Refusal.FAILED, files.createFolder(path("MAIN.glsl")));
		assertEquals(Set.of("Main.glsl"), stored(""));
		assertEquals("upper", read("Main.glsl"));
	}

	@Test
	public void decomposedNamesAreWrittenInPlace() {
		FakeDocuments.Node stored = documents.put(DECOMPOSED, "text");
		assertTrue(files.write(path(COMPOSED), bytes("new"),
				revision(COMPOSED)) instanceof Done);
		assertEquals("new", documents.text(stored));
		assertEquals(Set.of(DECOMPOSED), stored(""));
		assertEquals(new Done(null), files.rename(path(COMPOSED),
				path(COMPOSED)));
	}

	@Test
	public void namesThatNormalizeAlikeAreNotChanged() {
		documents.put(DECOMPOSED, "decomposed");
		documents.put(COMPOSED, "composed");
		assertRefused(Refusal.FAILED, files.write(path(COMPOSED),
				bytes("x"), null));
		assertRefused(Refusal.FAILED, files.delete(path(COMPOSED)));
		assertRefused(Refusal.FAILED, files.rename(path(COMPOSED),
				path("other.glsl")));
		assertEquals(Set.of(DECOMPOSED, COMPOSED), stored(""));
	}

	@Test
	public void lostPermissionRefusesChanges() {
		documents.put("main.glsl", "");
		documents.failure = new SecurityException("revoked");
		Refused lost = new Refused(Refusal.FAILED,
				SafProjectFiles.LOST_FOLDER);
		assertEquals(lost, files.write(path("main.glsl"), bytes("x"), null));
		assertEquals(lost, files.createFolder(path("a")));
		assertEquals(lost, files.rename(path("main.glsl"), path("b.glsl")));
		assertEquals(lost, files.delete(path("main.glsl")));
	}

	@Test
	public void aWriteThatFailsMidwayMayHaveChangedTheFile() {
		FakeDocuments failing = new FakeDocuments() {
			@Override
			public void write(String id, byte[] bytes)
					throws java.io.IOException {
				super.write(id, java.util.Arrays.copyOf(bytes, 2));
				throw new java.io.IOException("full");
			}

			@Override
			public void delete(String id) throws java.io.IOException {
				throw new java.io.IOException("busy");
			}
		};
		FakeDocuments.Node main = failing.put("main.glsl", "ORIGINAL");
		files = new SafProjectFiles(failing, failing.root.id, true);
		String revision = ((Content) files.read(path("main.glsl")))
				.revision();

		assertRefused(Refusal.PARTIAL, files.write(path("main.glsl"),
				bytes("NEW TEXT"), revision));
		assertEquals("NE", failing.text(main));
		assertRefused(Refusal.PARTIAL, files.write(path("new.glsl"),
				bytes("NEW TEXT"), null));
	}

	@Test
	public void failedWriteOfANewFileLeavesNothing() {
		FakeDocuments failing = new FakeDocuments() {
			@Override
			public void write(String id, byte[] bytes) {
				throw new IllegalStateException("full");
			}
		};
		files = new SafProjectFiles(failing, failing.root.id, true);
		assertEquals(new Refused(Refusal.FAILED, "the storage failed: full"),
				files.write(path("main.glsl"), bytes("x"), null));
		assertEquals(1, failing.nodes.size());
	}

	private void assertEveryChangeUnsupported(String revision) {
		ProjectPath main = path("main.glsl");
		assertRefused(Refusal.UNSUPPORTED, files.write(main, bytes("x"),
				revision));
		assertRefused(Refusal.UNSUPPORTED, files.write(path("new.glsl"),
				bytes("x"), null));
		assertRefused(Refusal.UNSUPPORTED, files.createFolder(path("a")));
		assertRefused(Refusal.UNSUPPORTED, files.rename(main,
				path("b.glsl")));
		assertRefused(Refusal.UNSUPPORTED, files.delete(main));
	}

	private String read(String path) {
		return new String(((Content) files.read(path(path))).bytes(),
				StandardCharsets.UTF_8);
	}

	private String revision(String path) {
		return ((Content) files.read(path(path))).revision();
	}

	private Set<String> stored(String folder) {
		Set<String> names = new TreeSet<>();
		for (FakeDocuments.Node node : documents.children(
				documents.folder(folder))) {
			names.add(node.name);
		}
		return names;
	}

	private static void assertRefused(Refusal refusal, Change change) {
		assertTrue(String.valueOf(change), change instanceof Refused);
		assertEquals(String.valueOf(change), refusal,
				((Refused) change).refusal());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	private static byte[] bytes(String text) {
		return text.getBytes(StandardCharsets.UTF_8);
	}
}
