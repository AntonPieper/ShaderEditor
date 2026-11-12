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

public class KeyboardSessionTest {
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
					Files.readAllLines(file.toPath()));
		}
	}

	@Test
	public void recordingReplays() {
		StringBuilder out = new StringBuilder();
		KeyboardSession session = new KeyboardSession(out);
		Document document = new Document(Text.of(
				"a \"q\" \\ é😀\uD800\n\tb\u0001"));
		document.select(3, 1);
		TextInput input = new TextInput(document, (a, b, c, d) -> {
		}, session);
		input.getExtractedText(5, true);
		input.getSurroundingText(2, 2);
		input.setSelection(4, 4);
		input.setComposingText("x\u0007", 1);
		input.setComposingRegion(9, 2);
		session.note("sendKeyEvent", true, 0, 67);
		document.type(1, 2, Text.EMPTY);
		input.beginBatchEdit();
		input.commitText("y\r\nz", 1);
		document.select(0, 2);
		input.deleteSurroundingTextInCodePoints(1, 1);
		input.endBatchEdit();
		document.undo();
		input.getTextBeforeCursor(3);
		input.getTextAfterCursor(3);
		input.getSelectedText();
		input.commitText(null, 0);
		input.deleteSurroundingText(1, 0);
		input.finishComposingText();
		input.endBatchEdit();
		input.beginBatchEdit();
		input.closeConnection();
		input.commitText("ignored", 1);
		String recorded = out.toString();
		assertTrue(recorded, recorded.endsWith("\n"));
		KeyboardReplay.replay("recording",
				List.of(recorded.split("\n")));
	}

	@Test
	public void stringsAreQuotedAndEscaped() {
		StringBuilder out = new StringBuilder();
		new KeyboardSession(out).note("call", null, "\"\\\n\t\ré", 1);
		assertEquals("# call \"\\\"\\\\\\n\\t\\u000d\\u00e9\" 1 -> null\n",
				out.toString());
	}

	@Test
	public void replayFailsAtTheFirstDifference() {
		List<String> session = List.of(
				"start 0 0 \"\"",
				"commitText \"a\" 1 -> true",
				"updateSelection 2 2 -1 -1",
				"end 1 1 \"a\"");
		AssertionError e = assertThrows(AssertionError.class,
				() -> KeyboardReplay.replay("wrong", session));
		assertTrue(e.getMessage(), e.getMessage().startsWith("wrong:3: "));
	}
}
