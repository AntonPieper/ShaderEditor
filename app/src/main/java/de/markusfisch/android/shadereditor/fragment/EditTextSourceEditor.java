package de.markusfisch.android.shadereditor.fragment;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.view.UndoRedo;
import de.markusfisch.android.shadereditor.widget.ShaderEditor;

/** The editor built on EditText, with the app's undo history. */
final class EditTextSourceEditor implements SourceEditor {
	@NonNull
	private final ShaderEditor shaderEditor;
	@NonNull
	private final UndoRedo undoRedo;

	EditTextSourceEditor(@NonNull ShaderEditor shaderEditor) {
		this.shaderEditor = shaderEditor;
		undoRedo = new UndoRedo(shaderEditor, ShaderEditorApp.editHistory);
	}

	@NonNull
	@Override
	public View getView() {
		return shaderEditor;
	}

	@Override
	public void setListener(@NonNull Listener listener) {
		shaderEditor.setOnEditPausedListener(listener::onEditPaused);
		shaderEditor.setOnTextModifiedListener(listener::onTextModified);
		shaderEditor.setOnCompletionsListener(listener::onCompletions);
	}

	@NonNull
	@Override
	public String getText() {
		CharSequence text = shaderEditor.getText();
		return text == null ? "" : text.toString();
	}

	@NonNull
	@Override
	public String getCleanText() {
		return shaderEditor.getCleanText();
	}

	@Override
	public void setText(@NonNull String text) {
		undoRedo.clearHistory();
		undoRedo.stopListeningForChanges();
		shaderEditor.setTextHighlighted(text);
		undoRedo.listenForChanges();
	}

	// The undo history is the process's, and the view state keeps the
	// selection.
	@Nullable
	@Override
	public Object getRetainedState() {
		return null;
	}

	@Override
	public void saveInstanceState(@NonNull Bundle state) {
	}

	@Override
	public void restoreText(@NonNull String text, @Nullable Object retained,
			@Nullable Bundle state) {
		// Before the view state restores the selection, and without
		// clearing the undo history, like EditText's own restore did.
		// The undo listener only starts in resume().
		shaderEditor.setTextHighlighted(text);
	}

	@Override
	public void insert(@NonNull CharSequence text) {
		shaderEditor.insert(text);
	}

	@Override
	public void addUniform(@NonNull String statement) {
		shaderEditor.addUniform(statement);
	}

	@Override
	public void navigateToLine(int line) {
		shaderEditor.navigateToLine(line);
	}

	@NonNull
	@Override
	public List<ProjectDiagnostic> getErrors() {
		return shaderEditor.getErrors();
	}

	@Override
	public void setErrors(@NonNull List<ProjectDiagnostic> errors) {
		shaderEditor.setErrors(errors);
	}

	@Override
	public void markErrors() {
		shaderEditor.updateErrorHighlighting();
	}

	@Override
	public void updateHighlighting() {
		shaderEditor.updateHighlighting();
	}

	@Override
	public boolean canUndo() {
		return undoRedo.canUndo();
	}

	@Override
	public void undo() {
		undoRedo.undo();
	}

	@Override
	public boolean canRedo() {
		return undoRedo.canRedo();
	}

	@Override
	public void redo() {
		undoRedo.redo();
	}

	@Override
	public void setShowLineNumbers(boolean show) {
		shaderEditor.setShowLineNumbers(show);
	}

	@Override
	public void applySettings(@NonNull Preferences preferences) {
		shaderEditor.setUpdateDelay(preferences.getUpdateDelay());
		shaderEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP,
				preferences.getTextSize());
		Typeface font = preferences.getFont();
		shaderEditor.setTypeface(font);
		String features = shaderEditor.getFontFeatureSettings();
		boolean isMono = font == Typeface.MONOSPACE;
		// Don't touch font features for the default MONOSPACE font as
		// this can impact performance.
		if (!isMono || features != null) {
			shaderEditor.setFontFeatureSettings(isMono ? null :
					preferences.useLigatures() ? "normal" : "calt off");
		}
	}

	@Override
	public void resume() {
		// Only start listening after EditText restored its content
		// to make sure the initial change is not recorded.
		undoRedo.listenForChanges();
	}

	@Override
	public void destroy() {
		undoRedo.detachListener();
	}
}
