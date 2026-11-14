package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.FileRecord.Kind;
import de.markusfisch.android.shadereditor.project.FileRecord.Request;

public class FileRecordTest {
	private final FileRecord record = new FileRecord(
			ProjectPath.parse("a.glsl").value());

	@Test
	public void adoptsOnlyTheCurrentRequest() {
		Request open = record.start(Kind.OPEN);
		record.end(open);
		Request again = record.start(Kind.OPEN);

		assertFalse(record.adoptOpened(open, new Document(Text.of("old")),
				"r1", -1, false));
		assertNull(record.document());
		assertTrue(record.adoptOpened(again, new Document(Text.of("new")),
				"r2", -1, false));
		assertEquals("r2", record.base());
	}

	@Test
	public void storedTextReplacesOnlyAnUntouchedDocument() {
		open("stored", "r1");
		Request check = record.start(Kind.CHECK);
		record.document().replace(0, 6, Text.of("mine"));

		assertFalse(record.adoptStored(check, check.document(),
				check.revision(), Text.of("theirs"), "r2"));

		assertEquals("mine", record.document().text().toString());
		assertTrue(record.document().isModified());
		assertEquals("r1", record.base());
	}

	@Test
	public void listenersHearTheTextWithItsBase() {
		open("stored", "r1");
		List<String> heard = new ArrayList<>();
		record.document().addListener(new Document.Listener() {
			@Override
			public void onUpdate() {
				heard.add(record.document().text() + " at " + record.base());
			}
		});

		Request check = record.start(Kind.CHECK);
		assertTrue(record.adoptStored(check, check.document(),
				check.revision(), Text.of("theirs"), "r2"));

		assertEquals(List.of("theirs at r2"), heard);
		assertFalse(record.document().isModified());
	}

	@Test
	public void startsWhatWaitedOnceTheOperationEnds() {
		Request save = record.start(Kind.SAVE);
		List<Request> started = new ArrayList<>();
		record.whenIdle(() -> started.add(record.start(Kind.CHECK)));

		assertTrue(started.isEmpty());
		assertTrue(record.end(save));
		assertEquals(Kind.CHECK, started.get(0).kind());
		assertEquals(started.get(0), record.pending());
	}

	@Test
	public void textIsHeldOnceTheLastWriteOfItIsAcknowledged() {
		open("stored", "r1");
		Document document = record.document();
		document.addListener(new Document.Listener() {
			@Override
			public void onChange(Document.Change change, Text before) {
				record.collect(change);
			}
		});
		assertNull(record.flush());

		document.replace(6, 6, Text.of("!"));
		FileRecord.Flush first = record.flush();
		assertEquals(-1, first.from());
		assertEquals("stored!", first.text().toString());
		assertEquals("r1", first.header().base());
		document.replace(7, 7, Text.of("?"));
		FileRecord.Flush second = record.flush();
		assertEquals(first.to(), second.from());
		assertEquals(1, second.changes().size());
		assertEquals("stored!?", second.text().toString());
		assertNull(record.flush());

		record.acknowledge(first, true);
		assertFalse(record.settled());
		record.acknowledge(second, false);
		assertFalse(record.settled());
		FileRecord.Flush again = record.flush();
		assertEquals(-1, again.from());
		record.acknowledge(again, true);
		assertTrue(record.settled());
		document.replace(8, 8, Text.of("x"));
		assertFalse(record.settled());
		record.acknowledge(again, true);
		assertFalse(record.settled());

		document.undo();
		document.undo();
		document.undo();
		assertTrue(record.flush().removes());
		assertFalse(record.journaled);
	}

	@Test
	public void atAFenceWhatIsNotAcknowledgedIsSentAgainWhole() {
		open("stored", "r1");
		Document document = record.document();
		document.replace(6, 6, Text.of("!"));
		FileRecord.Flush sent = record.flush();

		// Its answer is on its way, or lost: nothing more to send, but
		// at a fence the text goes again, whole.
		assertNull(record.flush());
		FileRecord.Flush again = record.flush(true);
		assertEquals(-1, again.from());
		assertEquals("stored!", again.text().toString());
		record.acknowledge(sent, false);
		assertFalse(record.settled());
		record.acknowledge(again, true);
		assertTrue(record.settled());
		assertNull(record.flush(true));

		// The same for a removal.
		document.undo();
		FileRecord.Flush remove = record.flush();
		assertTrue(remove.removes());
		assertNull(record.flush());
		assertTrue(record.flush(true).removes());
	}

	@Test
	public void anEarlierFailureCountsUntilTheLastWriteIsAnswered() {
		open("stored", "r1");
		record.document().replace(6, 6, Text.of("!"));
		FileRecord.Flush first = record.flush();
		FileRecord.Flush again = record.flush(true);

		// The first failed; whether the second held is not known yet.
		record.acknowledge(first, false);
		assertEquals("the journal cannot be written", record.failure);
		assertFalse(record.settled());

		record.acknowledge(again, true);
		assertNull(record.failure);
		assertTrue(record.settled());
	}

	@Test
	public void conflictIsWhatWasSeenOfStorageAgainstTheBase() {
		open("stored", "r1");
		Document document = record.document();
		Request check = record.start(Kind.CHECK);
		assertTrue(record.observe(check, FileTexts.Found.CHANGED, "r2"));
		record.end(check);
		// Without unsaved text nothing is in conflict.
		assertNull(record.conflict());

		document.replace(0, 6, Text.of("mine"));
		assertEquals("the file has changed", record.conflict());
		assertEquals("r2", record.seen);

		record.consent("r2");
		assertNull(record.conflict());
		assertEquals("r2", record.base());
		check = record.start(Kind.CHECK);
		record.observe(check, FileTexts.Found.CHANGED, "r3");
		record.end(check);
		assertEquals("r3", record.seen);
		assertEquals("the file has changed", record.conflict());

		check = record.start(Kind.CHECK);
		record.observe(check, FileTexts.Found.MISSING, null);
		record.end(check);
		// What a request that is over found is not seen.
		assertFalse(record.observe(check, FileTexts.Found.SAME, "r2"));
		assertEquals("the file was deleted", record.conflict());
		assertNull(record.seen);
	}

	@Test
	public void keepingEditsLeavesNoStateOfTheDocumentSaved() {
		open("stored", "r1");
		Document document = record.document();
		document.replace(0, 6, Text.of("mine"));
		Request check = record.start(Kind.CHECK);
		record.observe(check, FileTexts.Found.CHANGED, "r2");
		record.end(check);

		record.consent("r2");
		document.undo();

		// The text first opened is not what storage holds at r2.
		assertEquals("stored", document.text().toString());
		assertTrue(document.isModified());
		assertTrue(record.edited());
	}

	private void open(String text, String revision) {
		Request open = record.start(Kind.OPEN);
		assertTrue(record.adoptOpened(open, new Document(Text.of(text)),
				revision, -1, false));
		record.end(open);
	}
}
