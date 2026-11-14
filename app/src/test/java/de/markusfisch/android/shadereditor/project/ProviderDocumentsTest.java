package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public class ProviderDocumentsTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	// Laid out like the data directory of the app.
	private File data;
	private File root;
	private ProviderDocuments documents;

	@Before
	public void setUp() throws IOException {
		data = temp.newFolder("data");
		root = new File(data, "files/user-files");
		put(root, "main.frag");
		put(root, "lib/a.glsl");
		put(root, ".git/config");
		put(data, "databases/shaders.db");
		put(data, "shared_prefs/preferences.xml");
		put(data, "files/drafts/draft.glsl");
		put(data, "files/wallpaper/main.frag");
		put(data, "cache/thumbnail.png");
		link("databases", new File(data, "databases"));
		link("alias.glsl", new File(root, "main.frag"));
		link("lib/up", data);
		documents = new ProviderDocuments(root);
	}

	@Test
	public void resolvesDocumentsInsideTheRoot() throws IOException {
		assertEquals(root.getCanonicalFile(),
				documents.file(ProviderDocuments.ROOT));
		assertEquals(new File(root, "main.frag").getCanonicalFile(),
				documents.file("root/main.frag"));
		assertEquals(new File(root, "lib/a.glsl").getCanonicalFile(),
				documents.file("root/lib/a.glsl"));
	}

	@Test
	public void refusesHostileIds() throws IOException {
		List<String> hostile = new ArrayList<>(Arrays.asList(
				null,
				"",
				"ROOT",
				"root/",
				"/",
				"root//main.frag",
				"root/./main.frag",
				"root/lib/../main.frag",
				"root/main.frag/",
				"root/..",
				"root/../drafts/draft.glsl",
				"root/../../databases/shaders.db",
				"root/../../shared_prefs/preferences.xml",
				"root/../wallpaper/main.frag",
				"root/../../cache/thumbnail.png",
				"../databases/shaders.db",
				"root/%2e%2e/%2e%2e/databases/shaders.db",
				"root\\..\\..\\databases\\shaders.db",
				"root/..\\..\\databases",
				"root/databases",
				"root/databases/shaders.db",
				"root/alias.glsl",
				"root/lib/up",
				"root/lib/up/databases/shaders.db",
				"root/.git",
				"root/.git/config",
				"root/main\u0000.frag",
				"root/main.frag\n",
				"root/C:/main.frag",
				"root/file:///etc/passwd",
				"content://de.markusfisch.android.shadereditor.debug" +
						".documents/document/root",
				"root/missing.glsl",
				"root/main.gls\u006c\u0301",
				new File(root, "main.frag").getAbsolutePath(),
				new File(data, "databases/shaders.db").getAbsolutePath(),
				"/data/data/de.markusfisch.android.shadereditor/" +
						"databases/shaders.db"));
		for (String id : hostile) {
			assertThrows(String.valueOf(id), FileNotFoundException.class,
					() -> documents.file(id));
			assertFalse(String.valueOf(id),
					documents.isChild(ProviderDocuments.ROOT, id));
			assertThrows(String.valueOf(id), FileNotFoundException.class,
					() -> documents.children(id));
			assertThrows(String.valueOf(id), FileNotFoundException.class,
					() -> documents.create(id, "new.glsl", false));
			assertThrows(String.valueOf(id), FileNotFoundException.class,
					() -> documents.rename(id, "renamed.glsl"));
			assertThrows(String.valueOf(id), FileNotFoundException.class,
					() -> documents.delete(id));
		}
		assertEquals(Set.of("shaders.db"), onDisk(data, "databases"));
		assertEquals(Set.of("draft.glsl"), onDisk(data, "files/drafts"));
		assertEquals(Set.of("alias.glsl", "databases", "main.frag", "lib",
				".git"), onDisk(root, ""));
	}

	@Test
	public void refusesHostileNames() throws IOException {
		for (String name : Arrays.asList(null, "", ".", "..", "../x.glsl",
				"a/b.glsl", "/x.glsl", ".git", "a\\..\\x", "x\u0000.glsl")) {
			String label = String.valueOf(name);
			assertThrows(label, FileNotFoundException.class,
					() -> documents.create(ProviderDocuments.ROOT, name, false));
			assertThrows(label, FileNotFoundException.class,
					() -> documents.create("root/lib", name, true));
			assertThrows(label, FileNotFoundException.class,
					() -> documents.rename("root/main.frag", name));
		}
		// A drive or scheme only means one at the start of a path.
		for (String name : Arrays.asList("C:x.glsl", "content:x")) {
			assertThrows(name, FileNotFoundException.class,
					() -> documents.create(ProviderDocuments.ROOT, name, false));
			assertEquals("root/lib/" + name,
					documents.create("root/lib", name, false));
		}
		assertEquals(Set.of("alias.glsl", "databases", "main.frag", "lib",
				".git"), onDisk(root, ""));
		assertEquals(Set.of("a.glsl", "up", "C:x.glsl", "content:x"),
				onDisk(root, "lib"));
	}

	@Test
	public void listsChildrenWithoutLinksOrVersionControl()
			throws IOException {
		assertEquals(Set.of("root/main.frag", "root/lib"),
				documents.children(ProviderDocuments.ROOT).keySet());
		assertEquals(new File(root, "lib").getCanonicalFile(),
				documents.children(ProviderDocuments.ROOT).get("root/lib")
						.getCanonicalFile());
		assertEquals(Set.of("root/lib/a.glsl"),
				documents.children("root/lib").keySet());
		assertThrows(FileNotFoundException.class,
				() -> documents.children("root/main.frag"));
	}

	@Test
	public void decidesContainmentOnResolvedFiles() {
		String root = ProviderDocuments.ROOT;
		assertTrue(documents.isChild(root, "root/main.frag"));
		assertTrue(documents.isChild(root, "root/lib/a.glsl"));
		assertTrue(documents.isChild("root/lib", "root/lib/a.glsl"));
		assertFalse(documents.isChild(root, root));
		assertFalse(documents.isChild("root/lib", "root/main.frag"));
		assertFalse(documents.isChild("root/lib/a.glsl", "root/lib"));
		assertFalse(documents.isChild("root/main.frag", "root/main.frag"));
		assertFalse(documents.isChild("root/../..", "root/main.frag"));
	}

	@Test
	public void namesParents() throws FileNotFoundException {
		assertEquals("root/lib", ProviderDocuments.parent("root/lib/a.glsl"));
		assertEquals(ProviderDocuments.ROOT,
				ProviderDocuments.parent("root/main.frag"));
		assertThrows(FileNotFoundException.class,
				() -> ProviderDocuments.parent(ProviderDocuments.ROOT));
		assertThrows(FileNotFoundException.class,
				() -> ProviderDocuments.parent("root/../x"));
	}

	@Test
	public void createsNumberedNames() throws IOException {
		String root = ProviderDocuments.ROOT;
		assertEquals("root/new.glsl", documents.create(root, "new.glsl",
				false));
		assertEquals("root/new (1).glsl", documents.create(root,
				"new.glsl", false));
		assertEquals("root/main (1).frag", documents.create(root,
				"main.frag", false));
		assertEquals("root/lib/sub", documents.create("root/lib", "sub",
				true));
		assertEquals("root/lib/sub (1)", documents.create("root/lib", "sub",
				true));
		assertEquals("root/.hidden", documents.create(root, ".hidden",
				false));
		assertEquals("root/caf\u00e9.glsl", documents.create(root,
				"cafe\u0301.glsl", false));
		assertEquals(0, documents.file("root/new (1).glsl").length());
		assertTrue(documents.file("root/lib/sub (1)").isDirectory());
		assertThrows(FileNotFoundException.class,
				() -> documents.create("root/main.frag", "x.glsl", false));
		assertThrows(FileNotFoundException.class,
				() -> documents.create("root/missing", "x.glsl", false));
	}

	@Test
	public void renamesAndDeletes() throws IOException {
		assertEquals("root/renamed.glsl", documents.rename("root/main.frag",
				"renamed.glsl"));
		assertTrue(documents.file("root/renamed.glsl").isFile());
		assertEquals("root/lib/b.glsl", documents.rename("root/lib/a.glsl",
				"b.glsl"));
		assertThrows(FileNotFoundException.class,
				() -> documents.file("root/lib/a.glsl"));
		documents.create(ProviderDocuments.ROOT, "taken.glsl", false);
		assertThrows(FileNotFoundException.class,
				() -> documents.rename("root/renamed.glsl", "taken.glsl"));
		assertThrows(FileNotFoundException.class,
				() -> documents.rename(ProviderDocuments.ROOT, "x"));

		documents.delete("root/lib");
		documents.delete("root/renamed.glsl");
		assertThrows(FileNotFoundException.class,
				() -> documents.delete(ProviderDocuments.ROOT));
		assertEquals(Set.of("root/taken.glsl"),
				documents.children(ProviderDocuments.ROOT).keySet());
		assertEquals(Set.of("shaders.db"), onDisk(data, "databases"));
		assertEquals(Set.of("config"), onDisk(root, ".git"));
	}

	private static void put(File dir, String path) throws IOException {
		File file = new File(dir, path);
		File parent = file.getParentFile();
		assertTrue(parent.isDirectory() || parent.mkdirs());
		Files.write(file.toPath(), path.getBytes(StandardCharsets.UTF_8));
	}

	private void link(String name, File target) throws IOException {
		Files.createSymbolicLink(new File(root, name).toPath(),
				target.toPath());
	}

	private static Set<String> onDisk(File dir, String path) {
		return new TreeSet<>(Arrays.asList(new File(dir, path).list()));
	}
}
