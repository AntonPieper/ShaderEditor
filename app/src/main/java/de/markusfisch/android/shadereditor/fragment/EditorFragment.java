package de.markusfisch.android.shadereditor.fragment;

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
import java.util.UUID;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.ErrorAdapter;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectParseResult;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.Syntax;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.ErrorListModal;
import de.markusfisch.android.shadereditor.widget.ShaderEditor;
import de.markusfisch.android.shadereditor.widget.SignatureView;

public class EditorFragment extends Fragment implements ErrorListModal.Host {
	public static final String TAG = "EditorFragment";

	private static final String DRAFT_KEY = "editor_draft_key";

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
	private Knowledge.Scope pendingScope;
	@Nullable
	private Object pendingDocumentState;
	private boolean pendingShaderToy;

	@Nullable
	private ShaderEditor.OnEditPausedListener editPausedListener;
	@Nullable
	private ShaderEditor.OnTextModifiedListener textModifiedListener;
	@Nullable
	private ShaderEditor.CodeCompletionListener codeCompletionListener;
	@Nullable
	private OnSignatureListener signatureListener;
	@Nullable
	private OnShaderToyShaderListener shaderToyShaderListener;
	@Nullable
	private EditorDraft draft;
	@Nullable
	private DraftHolder draftHolder;
	private boolean textRestored;
	// The diagnostics of every file while the editor shows a file of a
	// folder, else null: then all are of the text shown.
	@Nullable
	private List<ProjectDiagnostic> allErrors;
	// The file of the folder whose errors the editor marks, or null.
	@Nullable
	private ProjectPath errorsFile;
	// Whether the picture is the last that worked, not of these errors.
	private boolean showsLastPicture;
	@Nullable
	private ErrorAdapter.OnItemClickListener errorListener;
	// What the list of errors can show of the current build.
	private final ListedErrors listed = new ListedErrors();
	// Whether code is shown over the picture, for its brightness limit.
	private final CodeShown codeShown = new CodeShown();

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
		editor.setAddsByFileRule(addsByFileRule);
		editor.setFileLinks(fileLinks);
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

			@Override
			public void onShaderToyShader(@NonNull String json) {
				if (shaderToyShaderListener != null) {
					shaderToyShaderListener.onShaderToyShader(json);
				}
			}

			@Override
			public void onSignature(
					@Nullable SignatureView.Signature signature) {
				if (signatureListener != null) {
					signatureListener.onSignature(signature);
				}
			}

			// A file is shown at a line as an error of the list leads
			// to its file.
			@Override
			public void onOpenFile(@NonNull String file, int line) {
				ProjectParseResult<ProjectPath> path = ProjectPath.parse(
						file);
				if (errorListener != null && path.isSuccess()) {
					errorListener.onItemClick(path.value(), line);
				}
			}
		});
		setShowLineNumbers(ShaderEditorApp.preferences.showLineNumbers());
		DraftHolder holder = getDraftHolder();
		boolean inMemory = state != null &&
				holder.key != null &&
				holder.key.equals(state.getString(DRAFT_KEY));
		String restored = restoreDraft(state);
		textRestored = restored != null;
		if (restored != null) {
			editor.restoreText(restored, inMemory ? holder.retained : null,
					state);
			pendingText = null;
		} else if (pendingText != null) {
			setText(pendingText);
			pendingText = null;
		}
		if (pendingDocument != null) {
			editor.showDocument(pendingDocument, pendingSyntax, pendingScope,
					pendingDocumentState, pendingShaderToy);
			pendingDocument = null;
			pendingSyntax = null;
			pendingScope = null;
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
			holder.key = UUID.randomUUID().toString();
			holder.text = text;
			// Write now: the process may be killed as soon as this
			// returns.
			StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
			try {
				getDraft().write(holder.key, text);
			} finally {
				StrictMode.setThreadPolicy(policy);
			}
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
			StrictMode.ThreadPolicy policy = StrictMode.allowThreadDiskWrites();
			try {
				getDraft().delete();
			} finally {
				StrictMode.setThreadPolicy(policy);
			}
		}
	}

	public boolean isTextRestored() {
		return textRestored;
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
			text = getDraft().read(key);
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
			draft = new EditorDraft(requireContext().getNoBackupFilesDir());
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

	/** Hears the call being written, and null when there is none. */
	public interface OnSignatureListener {
		void onSignature(@Nullable SignatureView.Signature signature);
	}

	public void setOnSignatureListener(
			@Nullable OnSignatureListener listener) {
		signatureListener = listener;
	}

	/** Hears when the text has become a shader from ShaderToy as JSON. */
	public interface OnShaderToyShaderListener {
		void onShaderToyShader(@NonNull String json);
	}

	public void setOnShaderToyShaderListener(
			@Nullable OnShaderToyShaderListener listener) {
		shaderToyShaderListener = listener;
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

	/**
	 * What a run reported is gone: a run starts, or nothing runs. What
	 * the next build reports is not known yet.
	 */
	public void clearError() {
		allErrors = null;
		errorsFile = null;
		editor.setErrors(Collections.emptyList());
		listed.unknown();
		listedErrorsChanged();
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

	/** A build reported errors, perhaps none. */
	public void setErrors(@NonNull List<ProjectDiagnostic> errors) {
		allErrors = null;
		errorsFile = null;
		editor.setErrors(errors);
		highlightErrors();
		listed.reported(!errors.isEmpty());
		listedErrorsChanged();
	}

	/**
	 * Set the diagnostics of every file of a folder; the editor marks
	 * the lines of those of file, the one it shows, and the list offers
	 * them all.
	 */
	public void setErrors(@NonNull List<ProjectDiagnostic> errors,
			@NonNull ProjectPath file) {
		markErrors(errors, file);
		listed.reported(!errors.isEmpty());
		listedErrorsChanged();
	}

	/**
	 * Show file with the diagnostics of the last build of its folder;
	 * reportFollows is whether a build of it reports next, which then
	 * decides when nothing was kept.
	 */
	public void keepErrors(@NonNull List<ProjectDiagnostic> errors,
			@NonNull ProjectPath file, boolean reportFollows) {
		markErrors(errors, file);
		listed.kept(!errors.isEmpty(), reportFollows);
		listedErrorsChanged();
	}

	// A text of its own: nothing kept, and a build may report next.
	public void keepNoErrors(boolean reportFollows) {
		listed.kept(false, reportFollows);
		listedErrorsChanged();
	}

	private void markErrors(@NonNull List<ProjectDiagnostic> errors,
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

	/**
	 * Whether the picture shown is the last that worked, while the
	 * errors are of a newer build; the list of errors says so.
	 */
	public void setShowsLastPicture(boolean shows) {
		showsLastPicture = shows;
	}

	// A child, so that the system makes the list again with this
	// fragment after a recreate, and the list asks it for its errors.
	public void showErrors() {
		new ErrorListModal().show(getChildFragmentManager(),
				ErrorListModal.TAG);
	}

	@NonNull
	@Override
	public ListedErrors.State getListedState() {
		return listed.state();
	}

	@NonNull
	@Override
	public List<ProjectDiagnostic> getListedErrors() {
		if (allErrors != null && errorListener != null) {
			return allErrors;
		}
		return editor != null ? editor.getErrors() : List.of();
	}

	@Override
	public boolean showsLastPicture() {
		return showsLastPicture;
	}

	@Override
	public void onErrorChosen(@Nullable ProjectPath file, int line) {
		if (allErrors != null && errorListener != null) {
			errorListener.onItemClick(file, line);
		} else {
			// The editor shows a single file, so the file is ignored.
			navigateToLine(line);
		}
	}

	// The list of errors, if open, shows them as they are now.
	private void listedErrorsChanged() {
		if (!isAdded()) {
			return;
		}
		Fragment list = getChildFragmentManager().findFragmentByTag(
				ErrorListModal.TAG);
		if (list instanceof ErrorListModal modal) {
			modal.update();
		}
	}

	/** Return whether lookUp explains word. */
	public boolean hasDocs(@NonNull String word) {
		return editor != null && editor.hasDocs(word);
	}

	/** Explain word, if the editor can. */
	public void lookUp(@NonNull String word) {
		if (editor != null) {
			editor.lookUp(word);
		}
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
	 * language and with what its file sees of other files; state is
	 * what getDocumentState returned for it before, or null. shaderToy
	 * is whether text put in that defines mainImage
	 * and no main is converted from ShaderToy's form. Return false when
	 * the editor in use cannot show a document.
	 */
	public boolean showDocument(@NonNull Document document,
			@NonNull Syntax syntax, @NonNull Knowledge.Scope scope,
			@Nullable Object state, boolean shaderToy) {
		if (editor == null) {
			if (!ShaderEditorApp.preferences.useNewEditor()) {
				return false;
			}
			pendingText = null;
			pendingDocument = document;
			pendingSyntax = syntax;
			pendingScope = scope;
			pendingDocumentState = state;
			pendingShaderToy = shaderToy;
			return true;
		}
		return editor.showDocument(document, syntax, scope, state,
				shaderToy);
	}

	/**
	 * Set whether text put into the document shown is converted from
	 * ShaderToy's form, when that is known only after it was shown.
	 */
	public void setShaderToy(boolean shaderToy) {
		if (editor == null) {
			pendingShaderToy = shaderToy;
		} else {
			editor.setShaderToy(shaderToy);
		}
	}

	/** Return what the view keeps of the document shown, or null. */
	@Nullable
	public Object getDocumentState() {
		return editor != null ? editor.getDocumentState() : null;
	}

	/** Put in a completion; see SourceEditor.complete. */
	public void complete(@NonNull String word, int typed) {
		editor.complete(word, typed);
	}

	/** Complete the lines naming files from links, none when null. */
	public void setFileLinks(@Nullable FileLinks links) {
		fileLinks = links;
		if (editor != null) {
			editor.setFileLinks(links);
		}
	}

	public void tab() {
		editor.tab();
	}

	/** Add statements by the rule files have; see ShaderEditor. */
	public void setAddsByFileRule(boolean byFileRule) {
		addsByFileRule = byFileRule;
		if (editor != null) {
			editor.setAddsByFileRule(byFileRule);
		}
	}

	// Whether statements are added by the rule files have.
	private boolean addsByFileRule;
	@Nullable
	private FileLinks fileLinks;

	public void addUniform(@Nullable String statement) {
		if (statement != null) {
			editor.addUniform(statement);
		}
	}

	public boolean isCodeVisible() {
		return editorContainer.getVisibility() == View.VISIBLE;
	}

	/**
	 * Whether code is shown over the picture: not hidden with the code
	 * button and not covered. Its listener hears every change.
	 */
	public boolean isCodeShown() {
		return codeShown.shown();
	}

	/** Tell listener whether code is shown, now and on every change. */
	public void setOnCodeShownListener(@Nullable CodeShown.Listener listener) {
		codeShown.listen(listener);
	}

	/**
	 * With nothing open a cover stands where the code is: the editor's
	 * view goes, and comes back when something is open.
	 */
	public void setCovered(boolean covered) {
		View view = getView();
		if (view != null) {
			// In the new editor the view is the code, which the code
			// button may have hidden.
			view.setVisibility(covered ||
					(view == editorContainer && codeShown.hidden())
					? View.GONE
					: View.VISIBLE);
		}
		codeShown.setCovered(covered);
	}

	public boolean toggleCode() {
		boolean visible = isCodeVisible();
		editorContainer.setVisibility(visible ? View.GONE : View.VISIBLE);
		if (visible) {
			SoftKeyboard.hide(getActivity(), editor.getView());
		}
		codeShown.setHidden(visible);
		return visible;
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		editor.setShowLineNumbers(showLineNumbers);
	}
}