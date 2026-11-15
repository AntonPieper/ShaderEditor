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
import java.util.Objects;
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
import de.markusfisch.android.shadereditor.project.ShaderLanguage;
import de.markusfisch.android.shadereditor.syntax.GlslCompletion;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.LexedLine;
import de.markusfisch.android.shadereditor.syntax.Syntax;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.SignatureView;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyJson;
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
	// A shader of the database is GLSL.
	private static final Syntax DATABASE = ShaderLanguage.GLSL.syntax();

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
	// Whether ShaderToy's form is converted, which a file is only in
	// the library's root.
	private boolean convert = true;
	// Whether the listener was told that the text is ShaderToy's JSON.
	private boolean toldJson;
	@NonNull
	private List<ProjectDiagnostic> errors = Collections.emptyList();
	// Where the new editor is the default; null elsewhere.
	@Nullable
	private final WordDocs docs;
	// What the file shown sees of other files, and the words completion
	// offers besides the language's own: the names they declare, then
	// the app's uniforms, made again when those names change.
	@NonNull
	private Knowledge.Scope scope = Knowledge.Scope.NONE;
	@NonNull
	private List<String> outside = Collections.emptyList();
	@NonNull
	private List<String> extraWords = BUILTIN_UNIFORMS;
	// The call the strip shows, or null.
	@Nullable
	private SignatureView.Signature signature;

	CodeViewSourceEditor(@NonNull CodeView view) {
		this.view = view;
		// The screen keeps it clear of the system bars and the keyboard.
		view.setOnApplyWindowInsetsListener((v, insets) -> {
			v.onApplyWindowInsets(NO_INSETS);
			return insets;
		});
		view.setAnalysisListener(this::analysed);
		if (view.getResources().getBoolean(R.bool.project_mode)) {
			docs = new WordDocs(view, new WordDocs.Host() {
				@NonNull
				@Override
				public Knowledge declared() {
					return view.getAnalysis().knowledge();
				}

				@NonNull
				@Override
				public Knowledge.Scope scope() {
					return scope;
				}

				@Override
				public int offset() {
					return document.selection().start();
				}

				@Override
				public int lineOf(int offset) {
					Text text = document.text();
					return text.lineOf(Math.max(0, Math.min(offset,
							text.length())));
				}

				@Override
				public void goToLine(int line) {
					navigateToLine(line + 1);
				}

				@Override
				public void open(@NonNull String file, int line) {
					if (listener != null) {
						listener.onOpenFile(file, line + 1);
					}
				}

				// The call the caret is in can be shown now.
				@Override
				public void onDocsRead() {
					complete();
				}

				@Override
				public void addUniform(@NonNull String statement) {
					addMarked(statement);
				}
			});
		} else {
			docs = null;
		}
		show(new Document(Text.EMPTY), DATABASE);
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
		scope = Knowledge.Scope.NONE;
		show(new Document(Text.of(text)), DATABASE);
		convert = true;
		if (listener != null) {
			listener.onTextModified();
			listener.onEditPaused(getText());
		}
	}

	@Override
	public boolean showDocument(@NonNull Document document,
			@NonNull Syntax syntax, @NonNull Knowledge.Scope scope,
			@Nullable Object state, boolean shaderToy) {
		show(document, syntax);
		this.scope = scope;
		// The text as it is now is not the user's doing: only what is
		// put in after is converted.
		converting = shaderToy ? getText() : null;
		convert = shaderToy;
		if (state instanceof int[] scroll && scroll.length == 2) {
			view.scrollTo(scroll[0], scroll[1]);
		} else {
			view.bringCaretIntoView();
		}
		return true;
	}

	// What is in the document now is as it was written; only what is
	// put in from here on is converted.
	@Override
	public void setShaderToy(boolean shaderToy) {
		if (shaderToy != convert) {
			converting = shaderToy ? getText() : null;
			convert = shaderToy;
		}
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
		show(restored, DATABASE);
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
	public void tab() {
		view.tab();
	}

	@Override
	public void addUniform(@NonNull String statement) {
		AddUniform.apply(document, statement);
		view.bringCaretIntoView();
	}

	// Adds statement as Add uniform does, for a fix: marks its line until
	// the next edit and says where it went.
	private void addMarked(@NonNull String statement) {
		int line = document.text().lineOf(AddUniform.apply(document,
				statement));
		view.markLine(line);
		view.announceForAccessibility(view.getResources().getString(
				R.string.a11y_added_uniform, statement.substring(0,
						statement.length() - 1), line + 1));
	}

	@Override
	public boolean hasDocs(@NonNull String word) {
		return docs != null && styles.syntax() == DATABASE && docs.has(word);
	}

	@Override
	public void lookUp(@NonNull String word) {
		if (hasDocs(word)) {
			docs.lookUp(word);
		}
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

	private void show(@NonNull Document document, @NonNull Syntax syntax) {
		view.removeCallbacks(editPaused);
		if (this.document != null) {
			release();
		}
		this.document = document;
		revision = document.revision();
		errors = Collections.emptyList();
		styles = new LineStyles(document, syntax, STYLE_BUDGET);
		// GLSL's words, in GLSL only.
		view.setLookUp(syntax == DATABASE ? docs : null);
		marks = new LineMarks(document);
		view.setDocument(document, styles, marks);
		view.setRowFix(syntax == DATABASE ? docs : null);
		document.addListener(documentListener);
		// JSON that is there when a file is opened is not put in.
		toldJson = beginsAsJson();
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
		if (convert && isShaderToySource(analysis.knowledge())) {
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
		tellShaderToyJson();
	}

	// A shader from ShaderToy as JSON begins as JSON does, which no
	// shader does: only then is the whole text looked at. Told once,
	// when the text becomes it.
	private void tellShaderToyJson() {
		String json = convert && listener != null && beginsAsJson()
				? getText()
				: null;
		boolean isJson = json != null && ShaderToyJson.recognizes(json);
		if (isJson && !toldJson) {
			listener.onShaderToyShader(json);
		}
		toldJson = isJson;
	}

	private boolean beginsAsJson() {
		Text text = document.text();
		String start = text.substring(0, Math.min(text.length(), 64)).trim();
		return start.startsWith("{") || start.startsWith("[");
	}

	// Offers the completions for the caret, from its line and the last
	// analysis; nothing while text is selected.
	private void complete() {
		Document.Selection selection = document.selection();
		if (listener == null) {
			return;
		} else if (!selection.isEmpty()) {
			signature(-1, 0);
			return;
		}
		Text text = document.text();
		int caret = selection.head();
		int n = text.lineOf(caret);
		int lineStart = text.lineStart(n);
		Analysis analysis = view.getAnalysis();
		Knowledge known = analysis.knowledge();
		LexedLine line = styles.runs(n).line();
		int column = caret - lineStart;
		// Functions with their parenthesis, unless one follows already.
		boolean parentheses = docs != null && styles.syntax() == DATABASE &&
				(column == line.text().length() ||
						line.text().charAt(column) != '(');
		styles.syntax().complete(line, column,
				lineStart, known,
				analysis.revision() == document.revision(), extraWords(),
				(words, position) -> {
					// The call shows behind the keys, not behind words.
					signature(words == GlslCompletion.KEYS ? n : -1, column);
					listener.onCompletions(parentheses
							? withParentheses(words, known)
							: words, position);
				});
	}

	// Tells the listener the call the caret at column of line is an
	// argument of, when it changed; none for a line of -1. The call is
	// found from the tokens of at most twenty lines, and what it calls
	// by two lookups, so a keypress pays microseconds for it.
	private void signature(int line, int column) {
		SignatureView.Signature found = null;
		if (line > -1 && docs != null && styles.syntax() == DATABASE) {
			Syntax.Call call = styles.syntax().call(
					n -> styles.runs(n).line(), line, column);
			if (call != null) {
				found = docs.signature(call.name(), call.argument());
			}
		}
		if (!Objects.equals(found, signature)) {
			signature = found;
			listener.onSignature(found);
		}
	}

	// The names other files declare for this one, then the app's
	// uniforms. The scope gives the same list until the names change,
	// so a keypress makes no list.
	@NonNull
	private List<String> extraWords() {
		List<String> names = scope.names();
		if (names != outside) {
			outside = names;
			if (names.isEmpty()) {
				extraWords = BUILTIN_UNIFORMS;
			} else {
				List<String> joined = new ArrayList<>(names);
				joined.addAll(BUILTIN_UNIFORMS);
				extraWords = joined;
			}
		}
		return extraWords;
	}

	// A function gets its parenthesis whether this file declares it or
	// a file it includes.
	@NonNull
	private List<String> withParentheses(@NonNull List<String> words,
			@NonNull Knowledge known) {
		List<String> result = GlslCompletion.withParentheses(words, known);
		if (outside.isEmpty()) {
			return result;
		}
		for (int i = 0, n = result.size(); i < n; ++i) {
			String word = result.get(i);
			if (word.endsWith("(")) {
				continue;
			}
			List<Knowledge.Symbol> found = scope.find(word);
			if (!found.isEmpty() && found.get(0).kind() ==
					Knowledge.Kind.FUNCTION) {
				result.set(i, word + "(");
			}
		}
		return result;
	}

	// Whether the text defines mainImage and no main, as ShaderToy's do;
	// ShaderToySource has the last word.
	private static boolean isShaderToySource(@NonNull Knowledge knowledge) {
		boolean mainImage = false;
		for (Knowledge.Symbol declaration : knowledge.symbols()) {
			if (declaration.kind() != Knowledge.Kind.FUNCTION) {
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
