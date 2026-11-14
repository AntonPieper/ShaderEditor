package de.markusfisch.android.shadereditor.fragment;

import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.WindowInsets;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.AddUniform;
import de.markusfisch.android.shadereditor.editor.Analysis;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineMarks;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.ShaderToySource;

/**
 * The editor built on CodeView: one document, its colours and its line
 * marks, made again for every new text.
 */
final class CodeViewSourceEditor implements SourceEditor {
	private static final String SELECTION = "code_view_selection";
	// The lines LineStyles tokenizes per edit: a screen and a margin.
	private static final int STYLE_BUDGET = 200;
	private static final Pattern TRAILING_WHITE_SPACE = Pattern.compile(
			"[\\t ]+$", Pattern.MULTILINE);
	private static final WindowInsets NO_INSETS =
			new WindowInsetsCompat.Builder().build().toWindowInsets();
	private static final List<String> BUILTIN_UNIFORMS = new ArrayList<>();

	static {
		for (UniformDef uniform : UniformTable.BUILTINS) {
			BUILTIN_UNIFORMS.add(uniform.getName());
		}
		Collections.sort(BUILTIN_UNIFORMS);
	}

	@NonNull
	private final CodeView view;
	private final Runnable editPaused = this::editPaused;
	private final Document.Listener documentListener = new Document.Listener() {
		@Override
		public void onUpdate() {
			updated();
		}

		@Override
		public void onRefused(String reason) {
			Snackbar.make(view, R.string.text_too_large,
					Snackbar.LENGTH_LONG).show();
		}
	};
	@Nullable
	private Listener listener;
	private Document document;
	private LineStyles styles;
	private LineMarks marks;
	private int revision;
	private int updateDelay = 1000;
	private int inputType = -1;
	// The text converted from ShaderToy's form last.
	@Nullable
	private String converting;
	// Whether ShaderToy's form is converted, which a file is not.
	private boolean convert = true;
	@NonNull
	private List<ProjectDiagnostic> errors = Collections.emptyList();

	CodeViewSourceEditor(@NonNull CodeView view) {
		this.view = view;
		// The screen keeps it clear of the system bars and the keyboard.
		view.setOnApplyWindowInsetsListener((v, insets) -> {
			v.onApplyWindowInsets(NO_INSETS);
			return insets;
		});
		view.setAnalysisListener(this::analysed);
		show(new Document(Text.EMPTY));
	}

	@NonNull
	@Override
	public View getView() {
		return view;
	}

	@Override
	public void setListener(@NonNull Listener listener) {
		this.listener = listener;
	}

	@NonNull
	@Override
	public String getText() {
		return document.text().toString();
	}

	@NonNull
	@Override
	public String getCleanText() {
		return TRAILING_WHITE_SPACE.matcher(getText()).replaceAll("");
	}

	@Override
	public void setText(@NonNull String text) {
		show(new Document(Text.of(text)));
		convert = true;
		if (listener != null) {
			listener.onTextModified();
			listener.onEditPaused(getText());
		}
	}

	@Override
	public boolean showDocument(@NonNull Document document,
			@Nullable Object state) {
		show(document);
		// Not the user's own text, which is not rewritten.
		converting = null;
		convert = false;
		if (state instanceof int[] scroll && scroll.length == 2) {
			view.scrollTo(scroll[0], scroll[1]);
		} else {
			view.bringCaretIntoView();
		}
		return true;
	}

	@Nullable
	@Override
	public Object getDocumentState() {
		return new int[]{view.getScrollX(), view.getScrollY()};
	}

	@Nullable
	@Override
	public Object getRetainedState() {
		return document.history();
	}

	@Override
	public void saveInstanceState(@NonNull Bundle state) {
		Document.Selection selection = document.selection();
		state.putIntArray(SELECTION, new int[]{selection.anchor(),
				selection.head()});
	}

	@Override
	public void restoreText(@NonNull String text, @Nullable Object retained,
			@Nullable Bundle state) {
		Document restored = new Document(Text.of(text));
		int[] selection = state != null ? state.getIntArray(SELECTION) : null;
		int length = restored.text().length();
		if (!(retained instanceof Document.History history &&
				restored.restore(history)) &&
				selection != null && selection.length == 2 &&
				selection[0] >= 0 && selection[0] <= length &&
				selection[1] >= 0 && selection[1] <= length) {
			restored.select(selection[0], selection[1]);
		}
		show(restored);
		convert = true;
		view.bringCaretIntoView();
	}

	@Override
	public void insert(@NonNull CharSequence text) {
		Document.Selection selection = document.selection();
		document.replace(selection.start(), selection.end(), Text.of(text));
		view.bringCaretIntoView();
	}

	@Override
	public void addUniform(@NonNull String statement) {
		AddUniform.apply(document, statement);
		view.bringCaretIntoView();
	}

	@Override
	public void navigateToLine(int line) {
		Text text = document.text();
		if (line < 1 || line > text.lineCount()) {
			return;
		}
		int start = text.lineStart(line - 1);
		Document.Selection selection = document.selection();
		if (selection.start() >= start &&
				selection.end() <= start + text.line(line - 1).length()) {
			return;
		}
		document.select(start, start);
		view.bringCaretIntoView();
	}

	@NonNull
	@Override
	public List<ProjectDiagnostic> getErrors() {
		return errors;
	}

	@Override
	public void setErrors(@NonNull List<ProjectDiagnostic> errors) {
		this.errors = errors;
	}

	@Override
	public void markErrors() {
		marks.set(document.revision(), errors);
		view.marksChanged();
	}

	@Override
	public boolean keepsErrorsUntilResult() {
		return true;
	}

	@Override
	public boolean showsErrorMessages() {
		return view.isShown();
	}

	@Override
	public void updateHighlighting() {
		// The colours are always current.
	}

	@Override
	public boolean canUndo() {
		return document.canUndo();
	}

	@Override
	public void undo() {
		document.undo();
		view.bringCaretIntoView();
	}

	@Override
	public boolean canRedo() {
		return document.canRedo();
	}

	@Override
	public void redo() {
		document.redo();
		view.bringCaretIntoView();
	}

	@Override
	public void setShowLineNumbers(boolean show) {
		view.setShowLineNumbers(show);
	}

	@Override
	public void applySettings(@NonNull Preferences preferences) {
		updateDelay = preferences.getUpdateDelay();
		view.setTextSize(TypedValue.COMPLEX_UNIT_SP, preferences.getTextSize());
		Typeface font = preferences.getFont();
		view.setTypeface(font);
		view.setFontFeatureSettings(font == Typeface.MONOSPACE ? null :
				preferences.useLigatures() ? "normal" : "calt off");
		view.setTabWidth(preferences.getTabWidth());
		view.setTabIndents(preferences.useTabForIndent());
		int type = preferences.hideNativeSuggestions()
				? CodeView.INPUT_TYPE_NO_SUGGESTIONS
				: CodeView.INPUT_TYPE_TEXT;
		if (type != inputType) {
			inputType = type;
			view.setInputType(type);
		}
	}

	@Override
	public void resume() {
	}

	@Override
	public void destroy() {
		view.removeCallbacks(editPaused);
		// A file's document outlives this screen.
		release();
	}

	private void show(@NonNull Document document) {
		view.removeCallbacks(editPaused);
		if (this.document != null) {
			release();
		}
		this.document = document;
		revision = document.revision();
		errors = Collections.emptyList();
		styles = new LineStyles(document, GlslSyntax.INSTANCE, STYLE_BUDGET);
		marks = new LineMarks(document);
		view.setDocument(document, styles, marks);
		document.addListener(documentListener);
	}

	private void release() {
		document.removeListener(documentListener);
		styles.close();
		marks.close();
	}

	private void updated() {
		complete();
		if (document.revision() == revision) {
			return;
		}
		revision = document.revision();
		if (listener != null) {
			listener.onTextModified();
		}
		view.removeCallbacks(editPaused);
		view.postDelayed(editPaused, updateDelay);
	}

	private void analysed(@NonNull Analysis analysis) {
		complete();
		if (convert && isShaderToySource(analysis.outline())) {
			String source = getText();
			// Not again after the conversion was undone.
			String converted = source.equals(converting)
					? null
					: ShaderToySource.convert(source);
			if (converted != null) {
				converting = source;
				document.beginBatch();
				document.replace(0, document.text().length(),
						Text.of(converted));
				document.select(0, 0);
				document.endBatch();
				view.bringCaretIntoView();
			}
		}
	}

	// Offers the completions for the caret, from its line and the last
	// analysis; nothing while text is selected.
	private void complete() {
		Document.Selection selection = document.selection();
		if (listener == null || !selection.isEmpty()) {
			return;
		}
		Text text = document.text();
		int caret = selection.head();
		int n = text.lineOf(caret);
		int lineStart = text.lineStart(n);
		Analysis analysis = view.getAnalysis();
		styles.syntax().complete(styles.runs(n).line(), caret - lineStart,
				lineStart, analysis.outline(),
				analysis.revision() == document.revision(), BUILTIN_UNIFORMS,
				listener::onCompletions);
	}

	// Whether the text defines mainImage and no main, as ShaderToy's do;
	// ShaderToySource has the last word.
	private static boolean isShaderToySource(@NonNull Outline outline) {
		boolean mainImage = false;
		for (Outline.Declaration declaration : outline.declarations()) {
			if (declaration.kind() != Outline.Kind.FUNCTION) {
				continue;
			}
			if ("main".equals(declaration.name())) {
				return false;
			}
			mainImage |= "mainImage".equals(declaration.name());
		}
		return mainImage;
	}

	private void editPaused() {
		if (listener != null) {
			listener.onEditPaused(getText());
		}
	}
}
