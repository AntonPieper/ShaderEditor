package de.markusfisch.android.shadereditor.fragment;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.StrictMode;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;
import de.markusfisch.android.shadereditor.view.UndoRedo;
import de.markusfisch.android.shadereditor.widget.ErrorListModal;
import de.markusfisch.android.shadereditor.widget.ShaderEditor;

public class EditorFragment extends Fragment {
	public static final String TAG = "EditorFragment";

	private static final String DRAFT_KEY = "editor_draft_key";
	// One thread for all drafts so that writes and deletes stay in order.
	private static final ExecutorService draftExecutor =
			Executors.newSingleThreadExecutor();

	private View editorContainer;
	private ShaderEditor shaderEditor;
	private UndoRedo undoRedo;
	// Text set before the view exists, for example from an intent
	// that started the activity.
	@Nullable
	private String pendingText;
	@Nullable
	private UndoRedo.EditHistory pendingHistory;

	@Nullable
	private ShaderEditor.OnEditPausedListener editPausedListener;
	@Nullable
	private ShaderEditor.OnTextModifiedListener textModifiedListener;
	@Nullable
	private ShaderEditor.CodeCompletionListener codeCompletionListener;
	@Nullable
	private EditorDraft draft;
	@Nullable
	private DraftHolder draftHolder;

	/**
	 * The text at the last onSaveInstanceState(), kept in memory over a
	 * configuration change so that a rotation reads no file.
	 */
	public static final class DraftHolder extends ViewModel {
		@Nullable
		private String key;
		@Nullable
		private String text;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
		View view = inflater.inflate(R.layout.fragment_editor, container, false);

		editorContainer = view.findViewById(R.id.editor_container);
		shaderEditor = view.findViewById(R.id.editor);
		shaderEditor.setOnEditPausedListener((text) -> {
			if (editPausedListener != null) {
				editPausedListener.onEditPaused(text);
			}
		});
		shaderEditor.setOnTextModifiedListener(() -> {
			if (textModifiedListener != null) {
				textModifiedListener.onTextModified();
			}
		});
		shaderEditor.setOnCompletionsListener((completions, position) -> {
			if (codeCompletionListener != null) {
				codeCompletionListener.onCodeCompletions(completions, position);
			}
		});
		setShowLineNumbers(ShaderEditorApp.preferences.showLineNumbers());
		undoRedo = new UndoRedo(shaderEditor, ShaderEditorApp.editHistory);
		String restored = restoreDraft(state);
		if (restored != null) {
			// Before the view state restores the selection, and without
			// clearing the undo history, like EditText's own restore did.
			// The undo listener only starts in onResume().
			shaderEditor.setTextHighlighted(restored);
			pendingText = null;
		} else if (pendingText != null) {
			setText(pendingText, pendingHistory);
			pendingText = null;
			pendingHistory = null;
		}

		return view;
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (shaderEditor == null) {
			return;
		}
		DraftHolder holder = getDraftHolder();
		CharSequence current = shaderEditor.getText();
		String text = current == null ? "" : current.toString();
		if (holder.key == null || !text.equals(holder.text)) {
			holder.key = EditorDraft.newKey();
			holder.text = text;
			getDraft().save(holder.key, text);
		}
		outState.putString(DRAFT_KEY, holder.key);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (getActivity() != null && getActivity().isFinishing()) {
			// Nothing will be restored from it.
			getDraft().delete();
		}
	}

	@Nullable
	private String restoreDraft(@Nullable Bundle state) {
		String key = state == null ? null : state.getString(DRAFT_KEY);
		if (key == null) {
			return null;
		}
		DraftHolder holder = getDraftHolder();
		if (key.equals(holder.key)) {
			return holder.text;
		}
		// The process was killed in the background. This is one read of
		// a small file, once, and the text must be in place before the
		// first layout so that the scroll views restore their position.
		StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskReads();
		String text;
		try {
			text = getDraft().load(key);
		} finally {
			StrictMode.setThreadPolicy(policy);
		}
		if (text != null) {
			holder.key = key;
			holder.text = text;
		}
		return text;
	}

	@NonNull
	private DraftHolder getDraftHolder() {
		if (draftHolder == null) {
			draftHolder = new ViewModelProvider(this).get(DraftHolder.class);
		}
		return draftHolder;
	}

	@NonNull
	private EditorDraft getDraft() {
		if (draft == null) {
			Context context = requireContext().getApplicationContext();
			draft = new EditorDraft(context::getNoBackupFilesDir, draftExecutor);
		}
		return draft;
	}

	public void setOnTextModifiedListener(@Nullable ShaderEditor.OnTextModifiedListener listener) {
		textModifiedListener = listener;
	}

	public void setOnEditPausedListener(@Nullable ShaderEditor.OnEditPausedListener listener) {
		editPausedListener = listener;
	}

	public void setCodeCompletionListener(@Nullable ShaderEditor.CodeCompletionListener listener) {
		codeCompletionListener = listener;
	}

	@Override
	public void onDestroyView() {
		super.onDestroyView();
		undoRedo.detachListener();
	}

	@Override
	public void onResume() {
		super.onResume();
		updateToPreferences();
		// Only start listening after EditText restored its content
		// to make sure the initial change is not recorded.
		undoRedo.listenForChanges();
	}

	public void undo() {
		undoRedo.undo();
	}

	public boolean canUndo() {
		return undoRedo.canUndo();
	}

	public void redo() {
		undoRedo.redo();
	}

	public boolean canRedo() {
		return undoRedo.canRedo();
	}

	public boolean hasErrors() {
		return shaderEditor.hasErrors();
	}

	public void clearError() {
		shaderEditor.setErrors(Collections.emptyList());
	}

	public void updateHighlighting() {
		shaderEditor.updateHighlighting();
	}

	public void highlightErrors() {
		shaderEditor.updateErrorHighlighting();
	}

	public void setErrors(@NonNull List<ProjectDiagnostic> errors) {
		shaderEditor.setErrors(errors);
		highlightErrors();
	}

	public void showErrors() {
		List<ProjectDiagnostic> errors = shaderEditor.getErrors();
		// The editor shows a single file, so the file is ignored.
		new ErrorListModal(errors, (file, lineNumber) -> navigateToLine(lineNumber))
				.show(getParentFragmentManager(), ErrorListModal.TAG);
	}

	public void navigateToLine(int lineNumber) {
		shaderEditor.navigateToLine(lineNumber);
	}


	public String getText() {
		return shaderEditor.getCleanText();
	}

	// The text as it is in the editor, which its undo history refers
	// to; unlike getText(), trailing white space is kept.
	public String getRawText() {
		return shaderEditor.getText() != null
				? shaderEditor.getText().toString()
				: "";
	}

	public void setText(String text) {
		setText(text, null);
	}

	// Shows text with history as its undo history, which the caller
	// keeps for the file it belongs to, or with the cleared history of
	// database shaders when history is null.
	public void setText(String text, @Nullable UndoRedo.EditHistory history) {
		if (shaderEditor == null) {
			pendingText = text;
			pendingHistory = history;
			return;
		}
		clearError();
		undoRedo.detachListener();
		undoRedo = new UndoRedo(shaderEditor, history != null
				? history
				: ShaderEditorApp.editHistory);
		if (history == null) {
			undoRedo.clearHistory();
		}
		undoRedo.stopListeningForChanges();
		shaderEditor.setTextHighlighted(text);
		undoRedo.listenForChanges();
	}

	public void insert(@NonNull CharSequence text) {
		shaderEditor.insert(text);
	}

	public void addUniform(String name) {
		shaderEditor.addUniform(name);
	}

	public boolean isCodeVisible() {
		return editorContainer.getVisibility() == View.VISIBLE;
	}

	public boolean toggleCode() {
		boolean visible = isCodeVisible();
		editorContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
		if (visible) {
			SoftKeyboard.hide(getActivity(), shaderEditor);
		}
		return visible;
	}

	private void updateToPreferences() {
		Preferences preferences = ShaderEditorApp.preferences;
		shaderEditor.setUpdateDelay(preferences.getUpdateDelay());
		shaderEditor.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferences.getTextSize());
		Typeface font = preferences.getFont();
		shaderEditor.setTypeface(font);
		String features = shaderEditor.getFontFeatureSettings();
		boolean isMono = font == Typeface.MONOSPACE;
		// Don't touch font features for the default MONOSPACE font as
		// this can impact performance.
		if (!isMono || features != null) {
			shaderEditor.setFontFeatureSettings(isMono ? null : preferences.useLigatures() ?
					"normal" : "calt off");
		}
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		shaderEditor.setShowLineNumbers(showLineNumbers);
	}
}