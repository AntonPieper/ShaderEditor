package de.markusfisch.android.shadereditor.fragment;

import android.content.Context;
import android.os.Bundle;
import android.os.StrictMode;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.ErrorAdapter;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.syntax.Syntax;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.ErrorListModal;
import de.markusfisch.android.shadereditor.widget.ShaderEditor;

public class EditorFragment extends Fragment {
	public static final String TAG = "EditorFragment";

	private static final String DRAFT_KEY = "editor_draft_key";
	// One thread for all drafts so that writes and deletes stay in order.
	private static final ExecutorService draftExecutor =
			Executors.newSingleThreadExecutor();

	private View editorContainer;
	private SourceEditor editor;
	// Which editor the view was made with.
	private boolean newEditor;
	// Text set before the view exists, for example from an intent
	// that started the activity.
	@Nullable
	private String pendingText;
	// A document to show once the view exists, and its view state.
	@Nullable
	private Document pendingDocument;
	@Nullable
	private Syntax pendingSyntax;
	@Nullable
	private Object pendingDocumentState;

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
	// The diagnostics of every file while the editor shows a file of a
	// folder, else null: then all are of the text shown.
	@Nullable
	private List<ProjectDiagnostic> allErrors;
	// The file of the folder whose errors the editor marks, or null.
	@Nullable
	private ProjectPath errorsFile;
	@Nullable
	private ErrorAdapter.OnItemClickListener errorListener;

	/**
	 * The text at the last onSaveInstanceState(), kept in memory over a
	 * configuration change so that a rotation reads no file.
	 */
	public static final class DraftHolder extends ViewModel {
		@Nullable
		private String key;
		@Nullable
		private String text;
		// What the editor keeps in memory besides text.
		@Nullable
		private Object retained;
	}

	@Override
	public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
		View view;
		newEditor = ShaderEditorApp.preferences.useNewEditor();
		if (newEditor) {
			view = inflater.inflate(R.layout.fragment_code_view, container,
					false);
			editor = new CodeViewSourceEditor((CodeView) view);
			editorContainer = view;
		} else {
			view = inflater.inflate(R.layout.fragment_editor, container, false);
			editor = new EditTextSourceEditor(view.findViewById(R.id.editor));
			editorContainer = view.findViewById(R.id.editor_container);
		}
		editor.setListener(new SourceEditor.Listener() {
			@Override
			public void onTextModified() {
				if (textModifiedListener != null) {
					textModifiedListener.onTextModified();
				}
			}

			@Override
			public void onEditPaused(@NonNull String text) {
				if (editPausedListener != null) {
					editPausedListener.onEditPaused(text);
				}
			}

			@Override
			public void onCompletions(@NonNull List<String> words,
					int position) {
				if (codeCompletionListener != null) {
					codeCompletionListener.onCodeCompletions(words, position);
				}
			}
		});
		setShowLineNumbers(ShaderEditorApp.preferences.showLineNumbers());
		DraftHolder holder = getDraftHolder();
		boolean inMemory = state != null &&
				holder.key != null &&
				holder.key.equals(state.getString(DRAFT_KEY));
		String restored = restoreDraft(state);
		if (restored != null) {
			editor.restoreText(restored, inMemory ? holder.retained : null,
					state);
			pendingText = null;
		} else if (pendingText != null) {
			setText(pendingText);
			pendingText = null;
		}
		if (pendingDocument != null) {
			editor.showDocument(pendingDocument, pendingSyntax,
					pendingDocumentState);
			pendingDocument = null;
			pendingSyntax = null;
			pendingDocumentState = null;
		}

		return view;
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		if (editor == null) {
			return;
		}
		DraftHolder holder = getDraftHolder();
		String text = editor.getText();
		if (holder.key == null || !text.equals(holder.text)) {
			holder.key = EditorDraft.newKey();
			holder.text = text;
			getDraft().save(holder.key, text);
		}
		holder.retained = editor.getRetainedState();
		outState.putString(DRAFT_KEY, holder.key);
		editor.saveInstanceState(outState);
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
		editor.destroy();
	}

	@Override
	public void onResume() {
		super.onResume();
		if (newEditor != ShaderEditorApp.preferences.useNewEditor()) {
			// The draft keeps the text while the other editor is made.
			requireActivity().recreate();
			return;
		}
		editor.applySettings(ShaderEditorApp.preferences);
		editor.resume();
	}

	public void undo() {
		editor.undo();
	}

	public boolean canUndo() {
		return editor.canUndo();
	}

	public void redo() {
		editor.redo();
	}

	public boolean canRedo() {
		return editor.canRedo();
	}

	public boolean hasErrors() {
		return ProjectDiagnostic.anyError(allErrors != null
				? allErrors
				: editor.getErrors());
	}

	public void clearError() {
		allErrors = null;
		errorsFile = null;
		editor.setErrors(Collections.emptyList());
	}

	public void updateHighlighting() {
		editor.updateHighlighting();
	}

	public void highlightErrors() {
		editor.markErrors();
	}

	/**
	 * Called when the text is run again after an edit: clear the errors
	 * unless the editor keeps them until the result.
	 */
	public void runningEdit() {
		if (hasErrors() && !editor.keepsErrorsUntilResult()) {
			clearError();
			highlightErrors();
		}
	}

	/** Return whether the editor shows error next to its line now. */
	public boolean showsErrorMessage(@NonNull ProjectDiagnostic error) {
		return editor.showsErrorMessages() && error.line() > 0 &&
				(errorsFile == null || errorsFile.equals(error.file()));
	}

	public void setErrors(@NonNull List<ProjectDiagnostic> errors) {
		allErrors = null;
		errorsFile = null;
		editor.setErrors(errors);
		highlightErrors();
	}

	/**
	 * Set the diagnostics of every file of a folder; the editor marks
	 * the lines of those of file, the one it shows, and the list offers
	 * them all.
	 */
	public void setErrors(@NonNull List<ProjectDiagnostic> errors,
			@NonNull ProjectPath file) {
		List<ProjectDiagnostic> inFile = new ArrayList<>();
		for (ProjectDiagnostic error : errors) {
			if (file.equals(error.file())) {
				inFile.add(error);
			}
		}
		allErrors = errors;
		errorsFile = file;
		editor.setErrors(inFile);
		highlightErrors();
	}

	/**
	 * Call listener for an error chosen in the list while the editor
	 * shows a file of a folder.
	 */
	public void setOnErrorSelectedListener(
			@Nullable ErrorAdapter.OnItemClickListener listener) {
		errorListener = listener;
	}

	public void showErrors() {
		if (allErrors != null && errorListener != null) {
			new ErrorListModal(allErrors, errorListener)
					.show(getParentFragmentManager(), ErrorListModal.TAG);
			return;
		}
		List<ProjectDiagnostic> errors = editor.getErrors();
		// The editor shows a single file, so the file is ignored.
		new ErrorListModal(errors, (file, lineNumber) -> navigateToLine(lineNumber))
				.show(getParentFragmentManager(), ErrorListModal.TAG);
	}

	public void navigateToLine(int lineNumber) {
		editor.navigateToLine(lineNumber);
	}


	public String getText() {
		return editor.getCleanText();
	}

	public void setText(String text) {
		if (editor == null) {
			pendingText = text;
			pendingDocument = null;
			return;
		}
		clearError();
		editor.setText(text == null ? "" : text);
	}

	/**
	 * Show document itself, with its undo history and selection, without
	 * telling the listeners of a change, with the syntax of its file's
	 * language; state is what getDocumentState returned for it before,
	 * or null. Return false when the editor in use cannot show a
	 * document.
	 */
	public boolean showDocument(@NonNull Document document,
			@NonNull Syntax syntax, @Nullable Object state) {
		if (editor == null) {
			if (!ShaderEditorApp.preferences.useNewEditor()) {
				return false;
			}
			pendingText = null;
			pendingDocument = document;
			pendingSyntax = syntax;
			pendingDocumentState = state;
			return true;
		}
		return editor.showDocument(document, syntax, state);
	}

	/** Return what the view keeps of the document shown, or null. */
	@Nullable
	public Object getDocumentState() {
		return editor != null ? editor.getDocumentState() : null;
	}

	public void insert(@NonNull CharSequence text) {
		editor.insert(text);
	}

	public void addUniform(@Nullable String statement) {
		if (statement != null) {
			editor.addUniform(statement);
		}
	}

	public boolean isCodeVisible() {
		return editorContainer.getVisibility() == View.VISIBLE;
	}

	public boolean toggleCode() {
		boolean visible = isCodeVisible();
		editorContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
		if (visible) {
			SoftKeyboard.hide(getActivity(), editor.getView());
		}
		return visible;
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		editor.setShowLineNumbers(showLineNumbers);
	}
}