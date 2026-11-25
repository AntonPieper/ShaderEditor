package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.KeyCommands.Command;

public class KeyCommandsTest {
	private static final int SHIFT = KeyEvent.META_SHIFT_ON;
	private static final int CTRL = KeyEvent.META_CTRL_ON;

	@Test
	public void keysMapToCommands() {
		Object[][] table = {
				{KeyEvent.KEYCODE_DPAD_LEFT, 0, Command.BACKWARD},
				{KeyEvent.KEYCODE_DPAD_LEFT, SHIFT, Command.BACKWARD},
				{KeyEvent.KEYCODE_DPAD_LEFT, CTRL, Command.WORD_BACKWARD},
				{KeyEvent.KEYCODE_DPAD_RIGHT, 0, Command.FORWARD},
				{KeyEvent.KEYCODE_DPAD_RIGHT, CTRL | SHIFT,
						Command.WORD_FORWARD},
				{KeyEvent.KEYCODE_DPAD_UP, 0, Command.LINE_UP},
				{KeyEvent.KEYCODE_DPAD_DOWN, SHIFT, Command.LINE_DOWN},
				{KeyEvent.KEYCODE_DPAD_DOWN, CTRL, null},
				{KeyEvent.KEYCODE_PAGE_UP, 0, Command.PAGE_UP},
				{KeyEvent.KEYCODE_PAGE_DOWN, 0, Command.PAGE_DOWN},
				{KeyEvent.KEYCODE_MOVE_HOME, 0, Command.LINE_START},
				{KeyEvent.KEYCODE_MOVE_HOME, CTRL, Command.START},
				{KeyEvent.KEYCODE_MOVE_END, 0, Command.LINE_END},
				{KeyEvent.KEYCODE_MOVE_END, CTRL | SHIFT, Command.END},
				{KeyEvent.KEYCODE_DEL, 0, Command.DELETE_BACKWARD},
				{KeyEvent.KEYCODE_DEL, CTRL, Command.DELETE_WORD_BACKWARD},
				{KeyEvent.KEYCODE_FORWARD_DEL, 0, Command.DELETE_FORWARD},
				{KeyEvent.KEYCODE_FORWARD_DEL, CTRL,
						Command.DELETE_WORD_FORWARD},
				{KeyEvent.KEYCODE_ENTER, 0, Command.ENTER},
				{KeyEvent.KEYCODE_NUMPAD_ENTER, 0, Command.ENTER},
				{KeyEvent.KEYCODE_TAB, 0, Command.INDENT},
				{KeyEvent.KEYCODE_TAB, SHIFT, Command.UNINDENT},
				{KeyEvent.KEYCODE_TAB, CTRL, null},
				{KeyEvent.KEYCODE_A, CTRL, Command.SELECT_ALL},
				{KeyEvent.KEYCODE_Z, CTRL, Command.UNDO},
				{KeyEvent.KEYCODE_Z, CTRL | SHIFT, Command.REDO},
				{KeyEvent.KEYCODE_Y, CTRL, Command.REDO},
				{KeyEvent.KEYCODE_X, CTRL, Command.CUT},
				{KeyEvent.KEYCODE_C, CTRL, Command.COPY},
				{KeyEvent.KEYCODE_V, CTRL, Command.PASTE},
				{KeyEvent.KEYCODE_A, 0, null},
				{KeyEvent.KEYCODE_S, CTRL, null},
		};
		for (Object[] row : table) {
			assertEquals("key " + row[0] + " meta " + row[1], row[2],
					KeyCommands.command((int) row[0], (int) row[1]));
		}
	}

	@Test
	public void contextMenuActionsMapToCommands() {
		assertEquals(Command.SELECT_ALL,
				KeyCommands.contextMenuCommand(android.R.id.selectAll));
		assertEquals(Command.PASTE,
				KeyCommands.contextMenuCommand(android.R.id.paste));
		assertEquals(Command.REDO,
				KeyCommands.contextMenuCommand(android.R.id.redo));
		assertEquals(null,
				KeyCommands.contextMenuCommand(android.R.id.switchInputMethod));
	}

	@Test
	public void printableKeysTypeUnlessCtrlIsHeld() {
		Document document = new Document(Text.of("ab"));
		document.select(1, 1);
		KeyCommands keys = keys(document, new String[1], true);
		assertTrue(keys.keyDown(KeyEvent.KEYCODE_X, SHIFT, "X", 10));
		assertFalse(keys.keyDown(KeyEvent.KEYCODE_S, CTRL, "s", 10));
		assertFalse(keys.keyDown(KeyEvent.KEYCODE_VOLUME_UP, 0, null, 10));
		assertEquals("aXb", document.text().toString());
	}

	// The Tab key on the screen sends no character; a hardware Tab does.
	@Test
	public void tabIndentsSelectedLinesWithOrWithoutItsCharacter() {
		for (String typed : new String[]{null, "\t"}) {
			Document document = new Document(Text.of("a\nb\nc"));
			document.select(0, 5);
			KeyCommands keys = keys(document, new String[1], true);
			assertTrue(keys.keyDown(KeyEvent.KEYCODE_TAB, 0, typed, 10));
			assertEquals("\ta\n\tb\n\tc", document.text().toString());
			assertTrue(keys.keyDown(KeyEvent.KEYCODE_TAB, SHIFT, typed, 10));
			assertEquals("a\nb\nc", document.text().toString());
		}
	}

	@Test
	public void cutKeepsTheSelectionIfTheClipboardRefusesIt() {
		Document document = new Document(Text.of("abc"));
		document.select(0, 2);
		String[] clip = new String[1];
		keys(document, clip, false).run(Command.CUT, false, 0);
		assertEquals("abc", document.text().toString());
		keys(document, clip, true).run(Command.CUT, false, 0);
		assertEquals("c", document.text().toString());
		assertEquals("ab", clip[0]);
	}

	@Test
	public void pasteIsOneStepWithTheCaretBehindIt() {
		Document document = new Document(Text.of("a-c"));
		document.select(1, 2);
		String[] clip = {"x\r\ny"};
		keys(document, clip, true).run(Command.PASTE, false, 0);
		assertEquals("ax\nyc", document.text().toString());
		assertEquals(new Document.Selection(4, 4), document.selection());
		document.undo();
		assertEquals("a-c", document.text().toString());
	}

	@Test
	public void aPasteTheDocumentRefusesChangesNothing() {
		Document document = new Document(Text.of(
				"x".repeat((int) Document.MAX_BYTES - 1)));
		document.select(1, 3);
		String[] refused = new String[1];
		document.addListener(new Document.Listener() {
			@Override
			public void onRefused(String reason) {
				refused[0] = reason;
			}
		});
		String[] clip = {"long"};
		keys(document, clip, true).run(Command.PASTE, false, 0);
		assertNotNull(refused[0]);
		assertEquals(new Document.Selection(1, 3), document.selection());
		assertEquals(0, document.revision());
		assertFalse(document.canUndo());
	}

	@Test
	public void aPasteComesWhenTheClipIsRead() {
		Document document = new Document(Text.of("a-c"));
		document.select(1, 2);
		List<KeyCommands.Pasted> waiting = new ArrayList<>();
		keys(document, waiting).run(Command.PASTE, false, 0);
		assertEquals("a-c", document.text().toString());
		waiting.get(0).paste(Text.of("x\ny"), 3);
		assertEquals("ax\nyc", document.text().toString());
		assertEquals(new Document.Selection(4, 4), document.selection());
	}

	// Input given before a clip comes runs once it came, in order, as if the
	// clip had come at once; no paste and no input is lost.
	@Test
	public void inputGivenWhileAClipIsReadRunsAfterIt() {
		assertEquals("abcxy|", late("abc|", "V", "V"));
		assertEquals("abcxq|", late("abc|", "V", "q"));
		assertEquals("abcx\n|", late("abc|", "V", "\n"));
		assertEquals("axq|d", late("a[bc]d", "V", "q"));
		assertEquals("abcx1y2z|", late("abc|", "V", "1", "V", "2", "V"));
		// Undo takes back the paste, not what was typed before it.
		assertEquals("abcd|", late("abc|", "d", "V", "Z"));
		// A selection made meanwhile grows over the clip.
		assertEquals("|", late("abc|", "V", "*", "D"));
	}

	// A caret put while the clip is read, by a tap, stays where it was put,
	// in front of the clip.
	@Test
	public void aCaretPutWhileAClipIsReadStaysInFrontOfIt() {
		assertEquals("|axbc", late("a|bc", "V", "^0"));
		assertEquals("aq|xd", late("a[bc]d", "V", "^1", "q"));
		assertEquals("aq|xd", late("a[bc]d", "V", "^2", "q"));
	}

	@Test
	public void undoAfterALatePasteTakesBackWhatCameLast() {
		Document document = new Document(Text.of("abc"), () -> 0);
		document.select(3, 3);
		List<KeyCommands.Pasted> waiting = new ArrayList<>();
		KeyCommands keys = keys(document, waiting);
		keys.run(Command.PASTE, false, 0);
		keys.keyDown(KeyEvent.KEYCODE_Q, 0, "q", 1);
		waiting.get(0).paste(Text.of("x"), 1);
		keys.run(Command.UNDO, false, 0);
		assertEquals("abcx", document.text().toString());
	}

	@Test
	public void aLaterPasteThatComesAtOnceWaitsForAnEarlierOne() {
		Document document = new Document(Text.of("abc"), () -> 0);
		document.select(3, 3);
		List<KeyCommands.Pasted> waiting = new ArrayList<>();
		String[] at = {null};
		KeyCommands keys = new KeyCommands(document,
				new EditCommands(document), new KeyCommands.Clipboard() {
			@Override
			public void read(KeyCommands.Pasted pasted) {
				if (at[0] == null) {
					waiting.add(pasted);
				} else {
					pasted.paste(Text.of(at[0]), 1);
				}
			}

			@Override
			public boolean set(Text text, int start, int end) {
				return false;
			}
		});
		keys.run(Command.PASTE, false, 0);
		at[0] = "y";
		keys.run(Command.PASTE, false, 0);
		waiting.get(0).paste(Text.of("x"), 1);
		assertEquals("abcxy", document.text().toString());
	}

	@Test
	public void nothingToPasteChangesNothing() {
		Document document = new Document(Text.of("abc"));
		List<KeyCommands.Pasted> waiting = new ArrayList<>();
		keys(document, waiting).run(Command.PASTE, false, 0);
		document.type(0, 0, Text.of("q"));
		waiting.get(0).paste(null, 0);
		assertEquals("qabc", document.text().toString());
	}

	@Test
	public void aPasteTakesTheCountOfItsBytes() {
		Document document = new Document(Text.of("abc"));
		List<KeyCommands.Pasted> waiting = new ArrayList<>();
		keys(document, waiting).run(Command.PASTE, false, 0);
		waiting.get(0).paste(Text.of("x"), Document.MAX_BYTES);
		assertEquals("abc", document.text().toString());
	}

	// text marks the selection with | or [ and ]; each input is V for a
	// paste, Z for undo, D for Backspace, * to select all, ^n for the caret
	// put at n, or what it types; the clips come after, x, y, ... in order.
	private static String late(String text, String... inputs) {
		int anchor = text.indexOf('[') >= 0 ? text.indexOf('[')
				: text.indexOf('|');
		String plain = text.replaceAll("[|\\[\\]]", "");
		int head = text.indexOf(']') >= 0 ? text.indexOf(']') - 1 : anchor;
		Document document = new Document(Text.of(plain), () -> 0);
		document.select(anchor, head);
		List<KeyCommands.Pasted> waiting = new ArrayList<>();
		KeyCommands keys = keys(document, waiting);
		for (String input : inputs) {
			switch (input) {
				case "V" -> keys.run(Command.PASTE, false, 0);
				case "Z" -> keys.run(Command.UNDO, false, 0);
				case "D" -> keys.keyDown(KeyEvent.KEYCODE_DEL, 0, null, 1);
				case "*" -> document.select(0, document.text().length());
				case "\n" -> keys.keyDown(KeyEvent.KEYCODE_ENTER, 0, "\n", 1);
				default -> {
					if (input.startsWith("^")) {
						int at = Integer.parseInt(input.substring(1));
						document.select(at, at);
					} else {
						keys.keyDown(KeyEvent.KEYCODE_Q, 0, input, 1);
					}
				}
			}
		}
		char clip = 'x';
		// A paste held for an earlier one asks only once that one came.
		for (int i = 0; i < waiting.size(); ++i) {
			waiting.get(i).paste(Text.of(String.valueOf(clip++)), 1);
		}
		String result = document.text().toString();
		Document.Selection selection = document.selection();
		return result.substring(0, selection.head()) + "|" +
				result.substring(selection.head());
	}

	// A clip comes when the test hands it to its waiting paste.
	private static KeyCommands keys(Document document,
			List<KeyCommands.Pasted> waiting) {
		return new KeyCommands(document, new EditCommands(document),
				new KeyCommands.Clipboard() {
					@Override
					public void read(KeyCommands.Pasted pasted) {
						waiting.add(pasted);
					}

					@Override
					public boolean set(Text text, int start, int end) {
						return false;
					}
				});
	}

	private static KeyCommands keys(Document document, String[] clip,
			boolean accepts) {
		return new KeyCommands(document, new EditCommands(document),
				new KeyCommands.Clipboard() {
					@Override
					public void read(KeyCommands.Pasted pasted) {
						Text text = clip[0] != null ? Text.of(clip[0]) : null;
						pasted.paste(text, text != null
								? Document.bytes(text)
								: 0);
					}

					@Override
					public boolean set(Text text, int start, int end) {
						if (accepts) {
							clip[0] = text.substring(start, end);
						}
						return accepts;
					}
				});
	}
}
