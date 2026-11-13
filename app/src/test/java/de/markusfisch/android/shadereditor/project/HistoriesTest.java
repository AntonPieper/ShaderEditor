package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

public class HistoriesTest {
	private static final ProjectPath A = ProjectPath.parse("a.glsl").value();

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File dir;
	private Document document;
	private String hash;

	@Before
	public void setUp() throws IOException {
		dir = temp.newFolder("history");
		document = new Document(Text.of("one\n"));
		document.replace(0, 3, Text.of("two"));
		document.select(1, 2);
		document.replace(3, 3, Text.of("\né \uD800 😀"));
		document.replace(0, 0, Text.of("// "));
		document.undo();
		hash = Histories.hash(document.text());
		assertTrue(new Histories(dir).write(A, hash, "sha256:base",
				document.history()));
	}

	@Test
	public void roundTripsAHistoryForItsTextOnly() {
		Histories.Kept kept = new Histories(dir).read(A, hash);
		Document restored = new Document(document.text());

		assertEquals("sha256:base", kept.base());
		assertTrue(restored.restore(kept.history()));
		assertEquals(document.selection(), restored.selection());
		restored.redo();
		document.redo();
		assertEquals(document.text().toString(), restored.text().toString());
		while (document.canUndo()) {
			document.undo();
			restored.undo();
			assertEquals(document.text().toString(),
					restored.text().toString());
			assertEquals(document.selection(), restored.selection());
		}
		assertFalse(restored.canUndo());

		Histories histories = new Histories(dir);
		assertNull(histories.read(A, Histories.hash(Text.of("other"))));
		assertFalse(histories.has(A));
	}

	@Test
	public void historyCutAnywhereIsIgnoredAndDeleted() throws IOException {
		File file = dir.listFiles()[0];
		byte[] full = Files.readAllBytes(file.toPath());

		for (int cut = 0; cut < full.length; ++cut) {
			Files.write(file.toPath(), Arrays.copyOf(full, cut));
			assertNull("cut at " + cut, new Histories(dir).read(A, hash));
			assertFalse("cut at " + cut, file.exists());
		}
		Files.write(file.toPath(), full);
		assertTrue(new Histories(dir).read(A, hash) != null);
	}
}
