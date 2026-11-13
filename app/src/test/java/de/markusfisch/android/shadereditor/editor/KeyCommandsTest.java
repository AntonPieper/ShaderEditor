package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

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
	public void printableKeysTypeUnlessCtrlIsHeld() {
		Document document = new Document(Text.of("ab"));
		document.select(1, 1);
		KeyCommands keys = keys(document, new String[1], true);
		assertTrue(keys.keyDown(KeyEvent.KEYCODE_X, SHIFT, "X", 10));
		assertFalse(keys.keyDown(KeyEvent.KEYCODE_S, CTRL, "s", 10));
		assertFalse(keys.keyDown(KeyEvent.KEYCODE_VOLUME_UP, 0, null, 10));
		assertEquals("aXb", document.text().toString());
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

	private static KeyCommands keys(Document document, String[] clip,
			boolean accepts) {
		return new KeyCommands(document, new EditCommands(document),
				new KeyCommands.Clipboard() {
					@Override
					public CharSequence get() {
						return clip[0];
					}

					@Override
					public boolean set(String text) {
						if (accepts) {
							clip[0] = text;
						}
						return accepts;
					}
				});
	}
}
