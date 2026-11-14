package de.markusfisch.android.shadereditor.fragment;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * What EditorFragment needs of the view that edits the shader's source.
 */
interface SourceEditor {
	interface Listener {
		/** Called after every edit and after a new text. */
		void onTextModified();

		/**
		 * Called with the text the update delay after the last edit,
		 * and at once after a new text.
		 */
		void onEditPaused(@NonNull String text);

		/**
		 * Called with the words to offer at the caret and how many
		 * characters of the word before the caret each repeats.
		 */
		void onCompletions(@NonNull List<String> words, int position);
	}

	/** Return the view that shows the text and takes the keyboard. */
	@NonNull
	View getView();

	void setListener(@NonNull Listener listener);

	/** Return the text as it is. */
	@NonNull
	String getText();

	/**
	 * Return the text without white space at the ends of its lines:
	 * what is saved, run and shared.
	 */
	@NonNull
	String getCleanText();

	/** Show text in place of the old one, without history and errors. */
	void setText(@NonNull String text);

	/**
	 * Show document itself, with its selection and undo history, in
	 * place of the text shown, without telling the listener: showing it
	 * is no edit. Edits go into document from then on. syntax is how
	 * the language of its file is coloured, completed and indented.
	 *
	 * @param state what getDocumentState returned when it was shown
	 *              before, or null
	 * @return false when this editor cannot show a document
	 */
	boolean showDocument(@NonNull Document document, @NonNull Syntax syntax,
			@Nullable Object state);

	/**
	 * Return what the view keeps of the document shown besides the
	 * document itself, such as where it scrolled to, or null.
	 */
	@Nullable
	Object getDocumentState();

	/**
	 * Return what is kept in memory over a configuration change besides
	 * the text, such as the undo history, or null.
	 */
	@Nullable
	Object getRetainedState();

	/**
	 * Put what is restored after the process ended besides the text into
	 * state; never the text, which can be too large for it.
	 */
	void saveInstanceState(@NonNull Bundle state);

	/**
	 * Show the text the editor showed before its view was made again,
	 * keeping the undo history.
	 *
	 * @param retained what getRetainedState returned then, if still in
	 *                 memory
	 * @param state    the saved instance state, if any
	 */
	void restoreText(@NonNull String text, @Nullable Object retained,
			@Nullable Bundle state);

	/** Replace the selection by text. */
	void insert(@NonNull CharSequence text);

	/** Add a uniform statement, in place of one of the same uniform. */
	void addUniform(@NonNull String statement);

	/** Put the caret at the start of line, from 1, unless it is in it. */
	void navigateToLine(int line);

	@NonNull
	List<ProjectDiagnostic> getErrors();

	/** Remember errors; markErrors marks their lines. */
	void setErrors(@NonNull List<ProjectDiagnostic> errors);

	/** Mark the lines of the errors last set, and no others. */
	void markErrors();

	/**
	 * Return whether the editor keeps showing the errors of the last
	 * run until the result of the next one replaces them, instead of
	 * having them cleared when an edit runs the text again.
	 */
	boolean keepsErrorsUntilResult();

	/**
	 * Return whether the editor shows the messages of errors next to
	 * their lines now, so that the screen need not show them.
	 */
	boolean showsErrorMessages();

	/** Colour the text again, after the settings changed. */
	void updateHighlighting();

	boolean canUndo();

	void undo();

	boolean canRedo();

	void redo();

	void setShowLineNumbers(boolean show);

	/**
	 * Apply the settings of the editor's appearance and behaviour, after
	 * they may have changed.
	 */
	void applySettings(@NonNull Preferences preferences);

	/** Called when the fragment resumes, after the text is restored. */
	void resume();

	/** Called when the view goes; the editor is not used after it. */
	void destroy();
}
