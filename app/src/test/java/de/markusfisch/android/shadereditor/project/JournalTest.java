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
import de.markusfisch.android.shadereditor.project.Journal.Move;
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
	public void afterATornTailTheTextIsWrittenWholeFromTheLastGoodRecord()
			throws IOException {
		assertTrue(journal.snapshot(HEADER, 0, Text.of("a")));
		assertEquals(Appended.DONE, journal.append(A, 0, 1,
				List.of(new Change(1, 0, "b"))));
		File file = journal.file(A);
		Files.write(file.toPath(), new byte[]{0, 0, 1},
				java.nio.file.StandardOpenOption.APPEND);

		// The text is read up to the last good record; nothing is
		// appended after the bytes that are no record.
		Journal reopened = new Journal(dir).own();
		assertEquals("ab", reopened.read(A).text().toString());
		assertEquals(Appended.SNAPSHOT, reopened.append(A, 1, 2,
				List.of(new Change(2, 0, "c"))));
		assertTrue(reopened.snapshot(HEADER, 2, Text.of("abc")));

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
	public void journalPutAsideIsKeptBesideAndTheFileHasNone() {
		assertTrue(journal.snapshot(HEADER, 3, Text.of("mine")));
		// Only the journal that holds exactly the write named.
		assertEquals(-1, journal.putAside(A, 2));
		assertEquals("mine", journal.read(A).text().toString());

		assertEquals(1, journal.putAside(A, 3));

		assertNull(new Journal(dir).read(A));
		assertTrue(new Journal(dir).headers().isEmpty());
		List<Journal.Replaced> replaced = new Journal(dir).replaced(A);
		assertEquals(1, replaced.size());
		assertEquals("mine", replaced.get(0).text().toString());
		// The journal written next is one like any other, and a later
		// reload keeps its text behind the first.
		assertTrue(journal.snapshot(HEADER, 4, Text.of("typed")));
		assertEquals(Appended.DONE, journal.append(A, 4, 5,
				List.of(new Change(5, 0, "!"))));
		assertEquals(2, journal.putAside(A, 5));
		replaced = journal.replaced(A);
		assertEquals(List.of("mine", "typed!"), List.of(
				replaced.get(0).text().toString(),
				replaced.get(1).text().toString()));
		assertEquals(-1, journal.putAside(
				ProjectPath.parse("none.glsl").value(), -1));

		journal.forget(A, 1);
		assertEquals(1, journal.replaced(A).size());
		assertEquals(2, journal.replaced(A).get(0).number());
	}

	@Test
	public void journalPutBackIsTheJournalUnlessOneWasWrittenSince() {
		assertTrue(journal.snapshot(HEADER, 3, Text.of("mine")));
		int aside = journal.putAside(A, 3);

		journal.putBack(A, aside);

		assertEquals("mine", journal.read(A).text().toString());
		assertEquals(3, journal.read(A).revision());
		assertTrue(journal.replaced(A).isEmpty());

		// One written since is the newer.
		aside = journal.putAside(A, 3);
		assertTrue(journal.snapshot(HEADER, 4, Text.of("newer")));
		journal.putBack(A, aside);
		assertEquals("newer", journal.read(A).text().toString());
		assertTrue(journal.replaced(A).isEmpty());
	}

	@Test
	public void readsTheMarkOfAJournalOfBeforeThatHeldReplacedText()
			throws IOException {
		assertTrue(journal.snapshot(new Header(A, "sha256:1", false, null,
				true), 3, Text.of("mine")));

		assertTrue(new Journal(dir).headers().get(A).replaced());
		// Put aside as it is, it reads like any text a reload replaced.
		assertEquals(1, journal.putAside(A, -1));
		assertEquals("mine", journal.replaced(A).get(0).text().toString());
	}

	@Test
	public void replacedTextFollowsARenameAndGoesWithADeletion() {
		ProjectPath b = ProjectPath.parse("b.glsl").value();
		assertTrue(journal.snapshot(HEADER, 1, Text.of("mine")));
		assertEquals(1, journal.putAside(A, 1));

		assertTrue(journal.follow(journal.noted(new Move(A, b))).files().isEmpty());

		assertTrue(journal.replaced(A).isEmpty());
		assertEquals("mine", journal.replaced(b).get(0).text().toString());
		assertTrue(journal.follow(journal.noted(new Move(b, null))).files().isEmpty());
		assertTrue(journal.replaced(b).isEmpty());
		assertEquals(0, dir.list().length);
	}

	@Test
	public void whateverFailsInARenameTheTextKeptIsUnderOneOfTheNames() {
		ProjectPath b = ProjectPath.parse("b.glsl").value();
		FaultyDisk disk = new FaultyDisk();
		for (int at = 0, operations = 1; at <= operations; ++at) {
			for (File file : dir.listFiles()) {
				assertTrue(file.delete());
			}
			Journal faulty = new Journal(dir, disk).own();
			assertTrue(faulty.snapshot(HEADER, 1, Text.of("kept")));
			assertEquals(1, faulty.putAside(A, 1));
			assertTrue(faulty.snapshot(HEADER, 2, Text.of("typed")));

			Journal.Note note = faulty.noted(new Move(A, b));
			disk.arm(at > 0 ? FaultyDisk.Fault.FAIL
					: FaultyDisk.Fault.NONE, at);
			boolean followed = faulty.follow(note).files().isEmpty();
			operations = Math.max(operations, disk.disarm());

			// Each text is there, under the old name or the new, or
			// under both when the old one could not be removed yet;
			// under the new alone when it was told so.
			Journal reader = new Journal(dir);
			List<Journal.Replaced> kept = new ArrayList<>(
					reader.replaced(A));
			kept.addAll(reader.replaced(b));
			assertFalse("operation " + at, kept.isEmpty());
			for (Journal.Replaced replaced : kept) {
				assertEquals("kept", replaced.text().toString());
				assertEquals(1, replaced.number());
			}
			Recovered journal = reader.read(b) != null
					? reader.read(b)
					: reader.read(A);
			assertEquals("operation " + at, "typed",
					journal.text().toString());
			if (followed) {
				assertNull(reader.read(A));
				assertTrue(reader.replaced(A).isEmpty());
			}
			// Asked again, they follow.
			assertTrue(faulty.follow(faulty.noted(new Move(A, b))).files().isEmpty());
			assertEquals("typed", reader.read(b).text().toString());
			assertEquals("kept", reader.replaced(b).get(0).text()
					.toString());
			faulty.release();
		}
	}

	@Test
	public void aTextKeptThatCannotBeReadNowStaysAndIsReadLater() {
		FaultyDisk disk = new FaultyDisk();
		Journal faulty = new Journal(dir, disk).own();
		assertTrue(faulty.snapshot(HEADER, 1, Text.of("kept")));
		assertEquals(1, faulty.putAside(A, 1));
		assertTrue(faulty.snapshot(HEADER, 2, Text.of("typed")));

		disk.denied = true;
		assertTrue(faulty.replaced(A).isEmpty());
		// Not damaged: only bytes that were read can be wrong.
		Recovered unread = faulty.read(A);
		assertNull(unread.text());
		assertFalse(unread.damaged());
		assertTrue(faulty.dismiss(A) == false);
		assertEquals(-1, faulty.putAside(A, 2));
		disk.denied = false;

		assertEquals("kept", faulty.replaced(A).get(0).text().toString());
		assertEquals("typed", faulty.read(A).text().toString());
	}

	@Test
	public void theNumberOfATextKeptIsItsOwnThroughARenameAndForGood() {
		ProjectPath b = ProjectPath.parse("b.glsl").value();
		for (int i = 1; i <= 2; ++i) {
			assertTrue(journal.snapshot(HEADER, i, Text.of("kept " + i)));
			assertEquals(i, journal.putAside(A, i));
		}
		journal.forget(A, 1);

		assertTrue(journal.follow(journal.noted(new Move(A, b))).files().isEmpty());

		// Not counted again from one: who knows it by 2 means this text.
		assertEquals(2, journal.replaced(b).get(0).number());
		assertTrue(journal.snapshot(new Header(b, "sha256:1", false, null),
				3, Text.of("kept 3")));
		assertEquals(3, journal.putAside(b, 3));
		journal.forget(b, 2);
		assertEquals(List.of("kept 3"), List.of(journal.replaced(b).get(0)
				.text().toString()));
		// Nor is a number another file's in the folder.
		assertTrue(journal.snapshot(HEADER, 1, Text.of("other")));
		assertEquals(4, journal.putAside(A, 1));
	}

	@Test
	public void aJournalThatCannotBePutBackStaysKept() {
		FaultyDisk disk = new FaultyDisk();
		Journal faulty = new Journal(dir, disk).own();
		assertTrue(faulty.snapshot(HEADER, 1, Text.of("mine")));
		int aside = faulty.putAside(A, 1);

		disk.arm(FaultyDisk.Fault.FAIL, 1);
		faulty.putBack(A, aside);
		disk.disarm();

		assertNull(faulty.read(A));
		assertEquals("mine", faulty.replaced(A).get(0).text().toString());
		// With a newer journal there, it goes only when that is whole.
		assertTrue(faulty.snapshot(HEADER, 2, Text.of("newer")));
		faulty.putBack(A, aside);
		assertTrue(faulty.replaced(A).isEmpty());
		assertEquals("newer", faulty.read(A).text().toString());
	}

	@Test
	public void aTextKeptBecomesTheJournalOfAFileThatHasNone() {
		for (int i = 1; i <= 2; ++i) {
			assertTrue(journal.snapshot(HEADER, i, Text.of("kept " + i)));
			assertEquals(i, journal.putAside(A, i));
		}

		assertTrue(journal.promote(A));

		// The newest; the older stays where undo finds it.
		assertEquals("kept 2", journal.read(A).text().toString());
		assertEquals("kept 1", journal.replaced(A).get(0).text()
				.toString());
		assertEquals(1, journal.replaced(A).size());
		// Never over a journal that is there.
		assertTrue(journal.promote(A));
		assertEquals("kept 2", journal.read(A).text().toString());
		assertEquals(1, journal.replaced(A).size());
	}

	@Test
	public void aNoteTouchesOnlyTheJournalItWasMadeFor() {
		ProjectPath b = ProjectPath.parse("b.glsl").value();
		Header first = new Header(A, null, false, null, false, 11);
		assertTrue(journal.snapshot(first, 1, Text.of("given up")));
		Journal.Note deletion = journal.noted(new Move(A, null));
		assertEquals(List.of(new Journal.Noted(journal.file(A).getName(),
				11)), deletion.files());
		assertTrue(journal.setNote(deletion));
		assertEquals(deletion, new Journal(dir).note());

		// Text typed afterwards is another journal under the same
		// name. The one that is there is not its earlier state: it is
		// put aside, not written over.
		assertTrue(journal.snapshot(new Header(A, null, false, null, false,
				12), 1, Text.of("typed since")));
		assertEquals("given up", journal.replaced(A).get(0).text()
				.toString());

		// Replayed, the note takes the journal it was made for, where
		// that was put, and not the one that has its name now.
		assertTrue(journal.follow(new Journal(dir).note()).files().isEmpty());
		assertEquals("typed since", journal.read(A).text().toString());
		assertTrue(journal.replaced(A).isEmpty());

		// A rename is for the journals that were there as well.
		assertEquals(1, journal.putAside(A, 1));
		assertTrue(journal.snapshot(new Header(A, null, false, null, false,
				14), 1, Text.of("typed on")));
		Journal.Note rename = journal.noted(new Move(A, b));
		assertTrue(journal.remove(A));
		assertTrue(journal.snapshot(new Header(A, null, false, null, false,
				13), 1, Text.of("of a new file")));
		assertTrue(journal.follow(rename).files().isEmpty());
		assertEquals("of a new file", journal.read(A).text().toString());
		assertNull(journal.read(b));
		// The text kept was noted, and follows.
		assertEquals("typed since", journal.replaced(b).get(0).text()
				.toString());
	}

	@Test
	public void aNoteWithoutNamesFromBeforeTouchesNothing()
			throws IOException {
		assertTrue(journal.snapshot(HEADER, 1, Text.of("kept")));
		java.io.ByteArrayOutputStream bytes =
				new java.io.ByteArrayOutputStream();
		java.io.DataOutputStream out = new java.io.DataOutputStream(bytes);
		out.writeInt(0x53454a31);
		out.writeUTF(A.toString());
		out.writeBoolean(false);
		out.writeUTF("");
		Files.write(new File(dir, "moving").toPath(), bytes.toByteArray());

		Journal.Note note = journal.note();
		assertEquals(new Move(A, null), note.move());
		assertTrue(journal.follow(note).files().isEmpty());
		assertEquals("kept", journal.read(A).text().toString());
	}

	@Test
	public void aJournalThatCouldNotBeReadIsPutAsideNotWrittenOver() {
		FaultyDisk disk = new FaultyDisk();
		Journal faulty = new Journal(dir, disk).own();
		Header first = new Header(A, null, false, null, false, 21);
		assertTrue(faulty.snapshot(first, 1, Text.of("hidden")));

		// Another journal instance, which has not read it, cannot read
		// it now, and is to write text of another identity there.
		disk.denied = true;
		Journal other = new Journal(dir, disk).own();
		assertFalse("read back fails too", other.snapshot(new Header(A,
				null, false, null, false, 22), 1, Text.of("typed")));
		disk.denied = false;
		assertNull(other.read(A));
		assertEquals("hidden", other.replaced(A).get(0).text().toString());
		assertTrue(other.snapshot(new Header(A, null, false, null, false,
				22), 1, Text.of("typed")));
		assertEquals("typed", other.read(A).text().toString());
		// Its own earlier state is written over, as ever.
		assertTrue(other.snapshot(new Header(A, null, false, null, false,
				22), 2, Text.of("typed on")));
		assertEquals(1, other.replaced(A).size());
	}

	@Test
	public void aReadThatCameShortIsNoReadAndCutsNothing() {
		FaultyDisk disk = new FaultyDisk();
		Journal faulty = new Journal(dir, disk).own();
		assertTrue(faulty.snapshot(HEADER, 1, Text.of("first")));
		int snapshot = (int) faulty.file(A).length();
		assertEquals(Appended.DONE, faulty.append(A, 1, 2,
				List.of(new Change(0, 5, "acknowledged latest"))));
		long whole = faulty.file(A).length();

		// A read gives only the snapshot, which is whole by itself.
		disk.cut = snapshot;
		Journal short_ = new Journal(dir, disk).own();
		Recovered read = short_.read(A);
		assertNull("fewer bytes than the file has is no text", read.text());
		assertFalse(read.damaged());
		// Nothing appends after bytes it did not read, and nothing is
		// cut, whatever fails next.
		disk.full = true;
		assertEquals(Appended.SNAPSHOT, short_.append(A, 1, 3,
				List.of(new Change(0, 5, "another"))));
		disk.full = false;
		disk.cut = -1;
		assertEquals(whole, short_.file(A).length());
		assertEquals("acknowledged latest",
				new Journal(dir).read(A).text().toString());
	}

	@Test
	public void bytesAfterTheLastWholeRecordAreNotCutButWrittenOver()
			throws IOException {
		assertTrue(journal.snapshot(HEADER, 1, Text.of("first")));
		File file = journal.file(A);
		Files.write(file.toPath(), new byte[]{1, 2, 3},
				java.nio.file.StandardOpenOption.APPEND);
		long torn = file.length();

		// No append after a tail that is no record: the whole text is
		// to be written again, by a file that is read back first.
		assertEquals(Appended.SNAPSHOT, new Journal(dir).own().append(A, 1, 2,
				List.of(new Change(5, 0, "!"))));
		assertEquals(torn, file.length());
	}

	@Test
	public void aJournalNoMemoryCouldReadIsNotDamagedAndIsNotDismissed() {
		assertTrue(journal.snapshot(HEADER, 1, Text.of("mine")));
		Recovered whole = journal.read(A);
		assertFalse(whole.damaged());
		// What a read that ran out of memory tells: no text, not damaged.
		Recovered unread = new Recovered(null, null, -1, "too large here",
				false, false);
		assertNull(unread.text());

		// A journal that reads is never dismissed.
		assertTrue(journal.dismiss(A));
		assertEquals("mine", journal.read(A).text().toString());
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
		assertTrue(journal.setNote(journal.noted(move)));
		assertEquals(move, new Journal(dir).note().move());

		assertTrue(journal.follow(journal.noted(move)).files().isEmpty());

		Recovered followed = new Journal(dir).read(moved);
		assertEquals(new Header(moved, "sha256:2", true, "sha256:2", true),
				followed.header());
		assertEquals(7, followed.revision());
		assertEquals("inner", followed.text().toString());
		assertNull(journal.read(inner));
		assertEquals("beside", journal.read(beside).text().toString());

		// One left behind goes when the new path holds exactly its
		// text: that one is its successor, read back now.
		assertTrue(journal.snapshot(kept, 7, Text.of("inner")));
		assertTrue(journal.follow(journal.noted(move)).files().isEmpty());
		assertEquals("inner", journal.read(moved).text().toString());
		assertNull(journal.read(inner));
		// That a journal is there under the new path proves nothing:
		// one that holds another text is another text, and both stay.
		assertTrue(journal.snapshot(kept, 7, Text.of("left behind")));
		assertTrue(journal.follow(journal.noted(move)).files().isEmpty());
		assertEquals("inner", journal.read(moved).text().toString());
		assertEquals("left behind", journal.read(inner).text().toString());
		assertTrue(journal.remove(inner));
		// One that cannot be read moves as it is.
		assertTrue(journal.snapshot(new Header(A, null, false, null), 1,
				Text.of("damaged")));
		File file = journal.file(A);
		byte[] bytes = Files.readAllBytes(file.toPath());
		bytes[bytes.length - 1] ^= 1;
		Files.write(file.toPath(), bytes);
		ProjectPath b = ProjectPath.parse("b.glsl").value();
		assertTrue(journal.follow(journal.noted(new Journal.Move(A, b))).files().isEmpty());
		assertFalse(file.exists());
		assertNull(journal.read(b).text());

		// A deletion takes them.
		assertTrue(journal.follow(journal.noted(new Journal.Move(
				ProjectPath.parse("shaders").value(), null))).files().isEmpty());
		assertNull(journal.read(moved));
		assertTrue(journal.setNote(null));
		assertNull(journal.note());
	}
}
