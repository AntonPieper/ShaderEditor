package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.Journal.Appended;
import de.markusfisch.android.shadereditor.project.Journal.Change;
import de.markusfisch.android.shadereditor.project.Journal.Header;
import de.markusfisch.android.shadereditor.project.Journal.Recovered;

public class JournalTest {
	private static final ProjectPath A = ProjectPath.parse("a.glsl").value();
	private static final Header HEADER = new Header(A, "sha256:1", false,
			null);

	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private File dir;
	private Journal journal;

	@Before
	public void setUp() throws IOException {
		dir = temp.newFolder("journal");
		journal = new Journal(dir);
	}

	@Test
	public void roundTripsEveryTextTheEditorAllows() {
		List<String> texts = List.of("", "no final line break",
				"a\n\uD800b\n", "ends in a lone surrogate \uDC00",
				"\u0000 nul\n", "emoji 😀 é\n",
				"x".repeat(1024 * 1024));
		for (String text : texts) {
			assertTrue(journal.snapshot(HEADER, 1, Text.of(text)));
			assertEquals(text, new Journal(dir).read(A).text().toString());
		}
		assertTrue(journal.snapshot(HEADER, 1, Text.EMPTY));
		String text = "";
		int revision = 1;
		for (String inserted : texts.subList(0, texts.size() - 1)) {
			assertEquals(Appended.DONE, journal.append(A, revision,
					revision + 1, List.of(new Change(0, 0, inserted))));
			text = inserted + text;
			++revision;
		}
		Recovered recovered = new Journal(dir).read(A);
		assertEquals(text, recovered.text().toString());
		assertEquals(revision, recovered.revision());
	}

	@Test
	public void cutAtEveryByteGivesTheTextOfTheLastCompleteRecord()
			throws IOException {
		String text = "start\n";
		assertTrue(journal.snapshot(HEADER, 0, Text.of(text)));
		File file = journal.file(A);
		List<Long> ends = new ArrayList<>();
		List<String> texts = new ArrayList<>();
		ends.add(file.length());
		texts.add(text);
		for (int i = 1; i <= 4; ++i) {
			Change change = i == 3
					? new Change(0, 6, "")
					: new Change(text.length(), 0, "line " + i + " é\n");
			assertEquals(Appended.DONE,
					journal.append(A, i - 1, i, List.of(change)));
			text = text.substring(0, change.start()) + change.inserted() +
					text.substring(change.start() + change.removed());
			ends.add(file.length());
			texts.add(text);
		}
		byte[] full = Files.readAllBytes(file.toPath());

		for (int cut = 0; cut <= full.length; ++cut) {
			File copy = temp.newFolder("cut" + cut);
			Files.write(new File(copy, file.getName()).toPath(),
					Arrays.copyOf(full, cut));
			Recovered recovered = new Journal(copy).read(A);
			int complete = -1;
			while (complete + 1 < ends.size() &&
					ends.get(complete + 1) <= cut) {
				++complete;
			}
			if (complete < 0) {
				assertNull("cut at " + cut, recovered.text());
				assertNotNull("cut at " + cut, recovered.problem());
			} else {
				assertEquals("cut at " + cut, texts.get(complete),
						recovered.text().toString());
				assertEquals("cut at " + cut, complete, recovered.revision());
			}
		}
	}

	@Test
	public void appendAfterACutTailContinuesFromTheLastGoodRecord()
			throws IOException {
		assertTrue(journal.snapshot(HEADER, 0, Text.of("a")));
		assertEquals(Appended.DONE, journal.append(A, 0, 1,
				List.of(new Change(1, 0, "b"))));
		File file = journal.file(A);
		Files.write(file.toPath(), new byte[]{0, 0, 1},
				java.nio.file.StandardOpenOption.APPEND);

		Journal reopened = new Journal(dir);
		assertEquals(Appended.DONE, reopened.append(A, 1, 2,
				List.of(new Change(2, 0, "c"))));

		Recovered recovered = new Journal(dir).read(A);
		assertEquals("abc", recovered.text().toString());
		assertEquals(2, recovered.revision());
	}

	@Test
	public void badChecksumCutsTheTailAndReportsTheSnapshot()
			throws IOException {
		assertTrue(journal.snapshot(HEADER, 0, Text.of("one\n")));
		assertEquals(Appended.DONE, journal.append(A, 0, 1,
				List.of(new Change(4, 0, "two\n"))));
		File file = journal.file(A);
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 6] ^= 1;
		Files.write(file.toPath(), bytes);

		Recovered tail = new Journal(dir).read(A);
		assertEquals("one\n", tail.text().toString());
		assertEquals(0, tail.revision());

		int text = new String(bytes, java.nio.charset.StandardCharsets
				.ISO_8859_1).indexOf("one\n");
		bytes[text] ^= 1;
		Files.write(file.toPath(), bytes);
		Recovered snapshot = new Journal(dir).read(A);
		assertNull(snapshot.text());
		assertNotNull(snapshot.problem());
		assertTrue(file.isFile());
		assertEquals(Appended.SNAPSHOT, new Journal(dir).append(A, 0, 1,
				List.of(new Change(0, 0, "x"))));
	}

	@Test
	public void appendThatDoesNotContinueTheJournalAsksForASnapshot() {
		List<Change> change = List.of(new Change(0, 0, "x"));
		assertEquals(Appended.SNAPSHOT, journal.append(A, 0, 1, change));
		assertTrue(journal.snapshot(HEADER, 3, Text.of("text")));

		assertEquals(Appended.SNAPSHOT, journal.append(A, 2, 4, change));
		assertEquals(Appended.DONE, journal.append(A, 3, 4, change));
		assertEquals(Appended.SNAPSHOT, journal.append(A, 3, 5, change));
		// Records beyond the snapshot's size are rewritten.
		assertEquals(Appended.SNAPSHOT, journal.append(A, 4, 5,
				List.of(new Change(0, 0, "y".repeat(17 * 1024)))));
		assertEquals("xtext", new Journal(dir).read(A).text().toString());
	}

	@Test
	public void journalGoneUnderItAsksForASnapshot() {
		assertTrue(journal.snapshot(HEADER, 0, Text.of("a")));
		assertTrue(journal.file(A).delete());

		assertEquals(Appended.SNAPSHOT, journal.append(A, 0, 1,
				List.of(new Change(1, 0, "b"))));
		assertFalse(journal.file(A).exists());
	}

	@Test
	public void headerKeepsTheKeptRevisionAcrossReopen() {
		Header kept = new Header(A, "sha256:2", true, "sha256:2");
		assertTrue(journal.snapshot(kept, 0, Text.of("mine")));
		assertEquals(Appended.DONE, journal.append(A, 0, 1,
				List.of(new Change(4, 0, "!"))));

		Journal reopened = new Journal(dir);
		assertEquals(kept, reopened.headers().get(A));
		assertEquals(kept, reopened.read(A).header());

		Header gone = new Header(A, null, true, null);
		assertTrue(reopened.setHeader(gone));
		Recovered recovered = new Journal(dir).read(A);
		assertEquals(gone, recovered.header());
		assertEquals("mine!", recovered.text().toString());
		assertEquals(1, recovered.revision());
		assertTrue(reopened.remove(A));
		assertNull(new Journal(dir).read(A));
		assertFalse(new Journal(dir).headers().containsKey(A));
	}

	@Test
	public void textOverTheLimitIsRecoveredButTooLarge() {
		String text = "x".repeat((int) FileTexts.MAX_BYTES + 1);
		assertTrue(journal.snapshot(HEADER, 0, Text.of(text)));

		Recovered recovered = new Journal(dir).read(A);

		assertTrue(recovered.tooLarge());
		assertEquals(text.length(), recovered.text().length());
		assertTrue(journal.sync());
	}
}
