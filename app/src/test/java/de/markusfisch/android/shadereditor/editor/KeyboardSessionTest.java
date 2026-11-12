package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document.Selection;

public class KeyboardSessionTest {
	private static final int KEYCODE_A = 29;
	private static final int KEYCODE_ENTER = 66;
	private static final int KEYCODE_DEL = 67;
	private static final int SELECT_ALL = 0x0102001f;

	private final long[] now = {0};

	@Test
	public void everySessionReplays() throws Exception {
		URL url = getClass().getClassLoader().getResource("keyboard-sessions");
		assertNotNull(url);
		File[] files = new File(url.toURI()).listFiles(
				(dir, name) -> name.endsWith(".txt"));
		assertNotNull(files);
		assertTrue(files.length > 0);
		Arrays.sort(files);
		for (File file : files) {
			KeyboardReplay.replay(file.getName(),
					Files.readAllLines(file.toPath()),
					KeyboardSessionTest::view);
		}
	}

	@Test
	public void recordingReplays() {
		KeyboardSession session = session(KeyboardSession.MAX_SIZE);
		Document document = new Document(Text.of(
				"a \"q\" \\ é😀\uD800\n\tb\u0001"), () -> now[0]);
		document.select(3, 1);
		TextInput input = new TextInput(document, (a, b, c, d) -> {
		}, session);
		input.getExtractedText(5, true);
		input.getSurroundingText(2, 2);
		input.setSelection(4, 4);
		input.setComposingText("x\u0007", 1);
		input.setComposingRegion(9, 2);
		session.note("requestCursorUpdates", false, 2);
		now[0] = 400;
		session.action("key", 0, KEYCODE_DEL, 0);
		view(document, "key", List.of("0", "" + KEYCODE_DEL, "0"));
		input.beginBatchEdit();
		input.commitText("y\r\nz", 1);
		session.action("select", 0, 2);
		document.select(0, 2);
		input.deleteSurroundingTextInCodePoints(1, 1);
		input.endBatchEdit();
		now[0] = 2000;
		session.action("undo");
		document.undo();
		session.action("redo");
		document.redo();
		input.getTextBeforeCursor(3);
		input.getTextAfterCursor(3);
		input.getSelectedText();
		input.commitText(null, 0);
		input.deleteSurroundingText(1, 0);
		input.finishComposingText();
		input.endBatchEdit();
		session.action("contextMenu", SELECT_ALL);
		view(document, "contextMenu", List.of("" + SELECT_ALL));
		input.beginBatchEdit();
		TextInput next = new TextInput(document, (a, b, c, d) -> {
		}, session);
		input.closeConnection();
		next.commitText("w", 1);
		next.closeConnection();
		input.commitText("ignored", 1);
		String recorded = session.drain();
		assertTrue(recorded, recorded.endsWith("\n"));
		assertEquals("", session.drain());
		KeyboardReplay.replay("recording", List.of(recorded.split("\n")),
				KeyboardSessionTest::view);
	}

	@Test
	public void replacedConnectionClosingLateLeavesTheNewOne() {
		KeyboardSession session = session(KeyboardSession.MAX_SIZE);
		Document document = new Document(Text.of("abc"));
		TextInput replaced = new TextInput(document, (a, b, c, d) -> {
		}, session);
		TextInput input = new TextInput(document, (a, b, c, d) -> {
		}, session);
		replaced.closeConnection();
		input.commitText("z", 1);
		input.closeConnection();
		KeyboardReplay.replay("overlap",
				List.of(session.drain().split("\n")));
	}

	@Test
	public void undoAndPausesAreReplayed() {
		// Undo takes only what was typed after the pause; replayed
		// without the pause or without undo, it would change other text.
		KeyboardReplay.replay("pause", List.of(
				"0 1 open 0 0 \"\"",
				"0 1 commitText \"a\" 1 -> true",
				"0 1 updateSelection 1 1 -1 -1",
				"1500 1 commitText \"b\" 1 -> true",
				"1500 1 updateSelection 2 2 -1 -1",
				"1600 app undo",
				"1600 1 changed 1 2 \"\"",
				"1600 1 updateSelection 1 1 -1 -1",
				"1700 1 close -> 1 1 \"a\""));
	}

	@Test
	public void sessionEndsAtItsSize() {
		KeyboardSession session = session(40);
		Document document = new Document(Text.of("abc"));
		TextInput input = new TextInput(document, (a, b, c, d) -> {
		}, session);
		input.commitText("y", 1);
		input.commitText("z", 1);
		String recorded = session.drain();
		assertEquals("0 1 open 0 0 \"abc\"\n0 - truncated\n", recorded);
		KeyboardReplay.replay("truncated", List.of(recorded.split("\n")));
	}

	@Test
	public void stringsAreQuotedAndEscaped() {
		KeyboardSession session = session(KeyboardSession.MAX_SIZE);
		session.note("call", null, "\"\\\n\t\ré", 1);
		assertEquals("# 0 call \"\\\"\\\\\\n\\t\\u000d\\u00e9\" 1 -> null\n",
				session.drain());
	}

	@Test
	public void replayFailsAtTheFirstDifference() {
		List<String> session = List.of(
				"0 1 open 0 0 \"\"",
				"0 1 commitText \"a\" 1 -> true",
				"0 1 updateSelection 2 2 -1 -1",
				"0 1 close -> 1 1 \"a\"");
		AssertionError e = assertThrows(AssertionError.class,
				() -> KeyboardReplay.replay("wrong", session));
		assertTrue(e.getMessage(), e.getMessage().startsWith("wrong:3: "));
	}

	private KeyboardSession session(int maxSize) {
		return new KeyboardSession(() -> now[0], maxSize);
	}

	// What the view does for the keys and context menu actions the
	// sessions use: letters, Enter, Backspace and select all.
	private static void view(Document document, String name,
			List<String> args) {
		Selection selection = document.selection();
		if ("contextMenu".equals(name)) {
			assertEquals(SELECT_ALL, Integer.parseInt(args.get(0)));
			document.select(0, document.text().length());
			return;
		}
		assertEquals("key", name);
		int keyCode = Integer.parseInt(args.get(1));
		if (!"0".equals(args.get(0))) {
			return;
		}
		if (keyCode == KEYCODE_DEL) {
			int head = selection.head();
			document.type(head - 1, head, Text.EMPTY);
		} else {
			document.type(selection.start(), selection.end(), Text.of(
					keyCode == KEYCODE_ENTER
							? "\n"
							: String.valueOf((char) ('a' + keyCode - KEYCODE_A))));
		}
	}
}
