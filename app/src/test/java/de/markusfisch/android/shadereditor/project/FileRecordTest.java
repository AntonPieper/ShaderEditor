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
				"r1"));
		assertNull(record.document());
		assertTrue(record.adoptOpened(again, new Document(Text.of("new")),
				"r2"));
		assertEquals("r2", record.base());
	}

	@Test
	public void storedTextReplacesOnlyAnUntouchedDocument() {
		open("stored", "r1");
		Request check = record.start(Kind.CHECK);
		record.document().replace(0, 6, Text.of("mine"));

		assertFalse(record.adoptStored(check, Text.of("theirs"), "r2"));

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

		assertTrue(record.adoptStored(record.start(Kind.CHECK),
				Text.of("theirs"), "r2"));

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

	private void open(String text, String revision) {
		Request open = record.start(Kind.OPEN);
		assertTrue(record.adoptOpened(open, new Document(Text.of(text)),
				revision));
		record.end(open);
	}
}
