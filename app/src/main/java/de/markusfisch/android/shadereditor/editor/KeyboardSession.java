package de.markusfisch.android.shadereditor.editor;

import de.markusfisch.android.shadereditor.editor.Document.Selection;
import de.markusfisch.android.shadereditor.editor.TextInput.Window;

/**
 * Hears what keyboards and the app do to documents through TextInput.
 * The debug build's KeyboardRecorder writes it down, so a session on a
 * device can be replayed in the unit tests.
 */
public interface KeyboardSession {
	KeyboardSession NONE = new KeyboardSession() {
	};

	/** The app is asked to do name, before it does it. */
	default void action(String name, Object... args) {
	}

	/** A call that TextInput does not take. */
	default void note(String name, Object answer, Object... args) {
	}

	/** Return the number of a new connection to document. */
	default int open(Document document, Selection selection) {
		return 0;
	}

	default void call(int connection, String name, Object[] args,
			Object answer) {
	}

	default void updateSelection(int connection, int selStart, int selEnd,
			int composingStart, int composingEnd) {
	}

	default void updateExtractedText(int connection, int token,
			Window window) {
	}

	default void changed(int connection, int from, int to, Text text) {
	}

	default void selected(int connection, Selection selection) {
	}

	default void close(int connection, Text text, Selection selection) {
	}
}
