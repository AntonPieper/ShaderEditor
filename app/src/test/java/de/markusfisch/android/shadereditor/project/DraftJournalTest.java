package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.DraftFiles.Draft;

public class DraftJournalTest {
	// "café" with a combining accent and an emoji.
	private static final String TEXT = "// café 🌈\nvoid main() {}";

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File dir;
	private DraftJournal journal;

	@Before
	public void setUp() throws IOException {
		dir = new File(temp.getRoot(), "drafts");
		journal = new DraftJournal(dir);
	}

	@Test
	public void keepsDraftsAcrossInstances() {
		assertTrue(journal.put(path("main.glsl"), new Draft(TEXT, "r1")));
		assertTrue(journal.put(path("lib/new.glsl"), new Draft("", null)));

		assertEquals(Map.of(
						path("main.glsl"), new Draft(TEXT, "r1"),
						path("lib/new.glsl"), new Draft("", null)),
				new DraftJournal(dir).drafts());
	}

	@Test
	public void writesTheTextOfADraftAndReadsItBack() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 2000; ++i) {
			sb.append("float caf\u00e9").append(i)
					.append(" = 1.; // \ud83c\udf08\n");
		}
		sb.append("x".repeat(20000));
		Text text = Text.of(sb);
		assertTrue(journal.put(path("main.glsl"), new Draft(text, "r1")));

		Draft draft = new DraftJournal(dir).drafts().get(path("main.glsl"));
		assertEquals(sb.toString(), draft.text().toString());
		assertEquals(text.lineCount(), draft.text().lineCount());
		assertEquals("r1", draft.base());
	}

	@Test
	public void replacesAndRemovesDrafts() {
		journal.put(path("main.glsl"), new Draft("one", "r1"));
		journal.put(path("main.glsl"), new Draft("two", "r1"));
		assertEquals(Map.of(path("main.glsl"), new Draft("two", "r1")),
				journal.drafts());
		assertEquals(1, draftFiles().length);

		assertTrue(journal.remove(path("main.glsl")));
		assertTrue(journal.remove(path("main.glsl")));
		assertTrue(journal.drafts().isEmpty());
	}

	@Test
	public void startsEmptyWithoutFolder() {
		assertTrue(journal.drafts().isEmpty());
		assertTrue(journal.remove(path("main.glsl")));
	}

	@Test
	public void ignoresHalfWrittenDrafts() throws IOException {
		journal.put(path("main.glsl"), new Draft("saved", "r1"));
		journal.put(path("lib.glsl"), new Draft("library", "r2"));
		File complete = draftFiles()[0];
		byte[] bytes = Files.readAllBytes(complete.toPath());
		// A temporary file a crash left behind and a cut-off draft.
		Files.write(new File(dir, ".journal-1.tmp").toPath(),
				Arrays.copyOf(bytes, bytes.length - 2));
		Files.write(complete.toPath(), Arrays.copyOf(bytes, bytes.length - 2));

		Map<ProjectPath, Draft> drafts = journal.drafts();

		assertEquals(1, drafts.size());
		assertTrue(complete.exists());
	}

	@Test
	public void ignoresForeignFiles() throws IOException {
		journal.put(path("main.glsl"), new Draft("saved", "r1"));
		File draft = draftFiles()[0];
		Files.write(new File(dir, "junk.draft").toPath(), new byte[]{1, 2});
		// A draft whose name does not belong to the path inside.
		Files.copy(draft.toPath(), new File(dir, "copy.draft").toPath());

		assertEquals(Map.of(path("main.glsl"), new Draft("saved", "r1")),
				journal.drafts());
	}

	private File[] draftFiles() {
		File[] files = dir.listFiles((d, name) -> name.endsWith(".draft"));
		return files != null ? files : new File[0];
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
