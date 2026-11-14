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

import de.markusfisch.android.shadereditor.editor.Document;
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
		journal = new Journal(dir).own();
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

		Journal reopened = new Journal(dir).own();
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
		assertEquals(Appended.SNAPSHOT, new Journal(dir).own().append(A, 0, 1,
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

		Journal reopened = new Journal(dir).own();
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
	public void textGrownOverTheLimitByItsRecordsIsToldTooLarge() {
		String text = "x".repeat((int) Document.MAX_BYTES);
		assertTrue(journal.snapshot(HEADER, 0, Text.of(text)));
		assertEquals(Appended.DONE, journal.append(A, 0, 1,
				List.of(new Change(0, 0, "y"))));

		Recovered recovered = new Journal(dir).read(A);

		assertTrue(recovered.tooLarge());
		assertEquals(text.length() + 1, recovered.text().length());
		assertTrue(journal.sync());
	}

	@Test
	public void onlyTheJournalThatOwnsTheFolderWritesAndDeletes() {
		assertTrue(journal.snapshot(HEADER, 0, Text.of("first")));
		Journal next = new Journal(dir).own();
		assertTrue(next.snapshot(HEADER, 0, Text.of("next")));

		assertFalse(journal.remove(A));
		assertFalse(journal.snapshot(HEADER, 1, Text.of("late")));
		assertEquals(Appended.FAILED, journal.append(A, 0, 1,
				List.of(new Change(0, 0, "x"))));
		assertFalse(journal.setHeader(HEADER));
		assertFalse(journal.dismiss(A));

		assertEquals("next", journal.read(A).text().toString());
		next.release();
		assertFalse(next.remove(A));
		assertEquals("next", next.read(A).text().toString());
	}

	@Test
	public void dismissDeletesOnlyAJournalThatCannotBeRead()
			throws IOException {
		assertTrue(journal.snapshot(HEADER, 0, Text.of("mine")));
		assertTrue(journal.dismiss(A));
		assertEquals("mine", journal.read(A).text().toString());
		File file = journal.file(A);
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);

		assertTrue(journal.dismiss(A));

		assertFalse(file.exists());
	}

	@Test
	public void readsNoMoreThanADocumentMayHold() throws IOException {
		// A snapshot that claims more than any text takes is not taken
		// into memory to find that out.
		assertTrue(journal.snapshot(HEADER, 0, Text.of("x")));
		File file = journal.file(A);
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(
				file, "rw")) {
			raf.seek(4);
			long at = 8 + raf.readInt() + 4;
			long claimed = 2 * Document.MAX_BYTES + 1;
			raf.seek(at);
			raf.writeInt((int) claimed);
			raf.setLength(at + 4 + claimed + 4);
		}
		Recovered claimed = new Journal(dir).read(A);
		assertNull(claimed.text());
		assertTrue(claimed.problem(), claimed.problem().contains("damaged"));

		// A text over the limit of a document is not built.
		assertTrue(journal.snapshot(HEADER, 0, Text.of("x".repeat(
				(int) Document.MAX_BYTES + 1))));
		Recovered large = new Journal(dir).read(A);
		assertNull(large.text());
		assertTrue(large.problem(), large.problem().contains("larger"));
		assertTrue(journal.snapshot(HEADER, 0, Text.of("\uD83D\uDE00".repeat(
				(int) Document.MAX_BYTES / 4))));
		assertEquals(Document.MAX_BYTES / 2,
				new Journal(dir).read(A).text().length());
	}

	@Test
	public void ownsTheFolderHoweverItsPathIsSpelled() {
		assertTrue(journal.snapshot(HEADER, 0, Text.of("first")));
		Journal alias = new Journal(new File(dir, ".")).own();
		assertTrue(alias.snapshot(HEADER, 0, Text.of("next")));

		assertFalse(journal.snapshot(HEADER, 1, Text.of("late")));
		assertFalse(journal.remove(A));
		assertEquals("next", journal.read(A).text().toString());
		boolean[] abandoned = {false};
		assertTrue(alias.remove(A));
		Journal.ifAbandoned(new File(dir.getParentFile(),
				dir.getName() + "/./"), () -> abandoned[0] = true);
		assertFalse(abandoned[0]);
		alias.release();
		Journal.ifAbandoned(dir, () -> abandoned[0] = true);
		assertTrue(abandoned[0]);
	}

	@Test
	public void replaysNoMoreRecordsThanAreEverAppended() throws IOException {
		Text text = Text.of("x".repeat(200 * 1024));
		List<Change> large = List.of(new Change(0, 0, "y".repeat(60_000)));
		assertTrue(journal.snapshot(HEADER, 0, text));
		assertEquals(Appended.DONE, journal.append(A, 0, 1, large));
		assertEquals(Appended.DONE, journal.append(A, 1, 2, large));
		// A third one is over what the records may take together.
		assertEquals(Appended.SNAPSHOT, journal.append(A, 2, 3, large));
		byte[] kept = Files.readAllBytes(journal.file(A).toPath());

		// Its bytes, as another journal that is at revision 2 writes
		// them, put behind the two all the same.
		assertTrue(journal.snapshot(HEADER, 2, text));
		long snapshot = journal.file(A).length();
		assertEquals(Appended.DONE, journal.append(A, 2, 3, large));
		byte[] other = Files.readAllBytes(journal.file(A).toPath());
		Files.write(journal.file(A).toPath(), kept);
		Files.write(journal.file(A).toPath(), Arrays.copyOfRange(other,
				(int) snapshot, other.length),
				java.nio.file.StandardOpenOption.APPEND);

		Recovered recovered = new Journal(dir).read(A);

		assertEquals(2, recovered.revision());
		assertEquals(text.length() + 120_000, recovered.text().length());
	}

	@Test
	public void headerKeepsThatAReloadReplacedTheText() throws IOException {
		assertTrue(journal.snapshot(HEADER, 3, Text.of("mine")));
		assertFalse(journal.header(A).replaced());
		// A header of before, which ends after the revision, says no.
		File file = journal.file(A);
		byte[] bytes = Files.readAllBytes(file.toPath());
		assertEquals(HEADER, new Journal(dir).headers().get(A));

		assertTrue(journal.markReplaced(A));

		Header marked = new Journal(dir).header(A);
		assertTrue(marked.replaced());
		assertEquals(HEADER.base(), marked.base());
		Recovered recovered = new Journal(dir).read(A);
		assertEquals(marked, recovered.header());
		assertEquals(3, recovered.revision());
		assertEquals("mine", recovered.text().toString());
		assertEquals(bytes.length, file.length());
		assertFalse(new Journal(dir).markReplaced(
				ProjectPath.parse("none.glsl").value()));
	}

	@Test
	public void journalsFollowARenameAndTheNewerOneStays()
			throws IOException {
		ProjectPath inner = ProjectPath.parse("lib/a.glsl").value();
		ProjectPath beside = ProjectPath.parse("libs.glsl").value();
		ProjectPath moved = ProjectPath.parse("shaders/a.glsl").value();
		Header kept = new Header(inner, "sha256:2", true, "sha256:2", true);
		assertTrue(journal.snapshot(kept, 7, Text.of("inner")));
		assertTrue(journal.snapshot(new Header(beside, null, false, null),
				1, Text.of("beside")));
		Journal.Move move = new Journal.Move(
				ProjectPath.parse("lib").value(),
				ProjectPath.parse("shaders").value());
		assertTrue(journal.setMove(move));
		assertEquals(move, new Journal(dir).move());

		assertTrue(journal.follow(move));

		Recovered followed = new Journal(dir).read(moved);
		assertEquals(new Header(moved, "sha256:2", true, "sha256:2", true),
				followed.header());
		assertEquals(7, followed.revision());
		assertEquals("inner", followed.text().toString());
		assertNull(journal.read(inner));
		assertEquals("beside", journal.read(beside).text().toString());

		// One written under the new path since is not written over by
		// one left behind; one that cannot be read moves as it is.
		assertTrue(journal.snapshot(kept, 7, Text.of("left behind")));
		assertTrue(journal.follow(move));
		assertEquals("inner", journal.read(moved).text().toString());
		assertNull(journal.read(inner));
		assertTrue(journal.snapshot(new Header(A, null, false, null), 1,
				Text.of("damaged")));
		File file = journal.file(A);
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		ProjectPath b = ProjectPath.parse("b.glsl").value();
		assertTrue(journal.follow(new Journal.Move(A, b)));
		assertFalse(file.exists());
		assertNull(journal.read(b).text());

		// A deletion takes them.
		assertTrue(journal.follow(new Journal.Move(
				ProjectPath.parse("shaders").value(), null)));
		assertNull(journal.read(moved));
		assertTrue(journal.setMove(null));
		assertNull(journal.move());
	}
}
