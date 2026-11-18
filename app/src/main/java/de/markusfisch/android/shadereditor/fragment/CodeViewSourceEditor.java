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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.AddUniform;
import de.markusfisch.android.shadereditor.editor.Analysis;
import de.markusfisch.android.shadereditor.editor.Definitions;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.DimmedLines;
import de.markusfisch.android.shadereditor.editor.LineMarks;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.project.BuiltLines;
import de.markusfisch.android.shadereditor.project.FileKind;
import de.markusfisch.android.shadereditor.project.HostCompletion;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.ProjectParseResult;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ShaderLanguage;
import de.markusfisch.android.shadereditor.syntax.GlslCompletion;
import de.markusfisch.android.shadereditor.syntax.GlslWords;
import de.markusfisch.android.shadereditor.syntax.Knowledge;
import de.markusfisch.android.shadereditor.syntax.LexedLine;
import de.markusfisch.android.shadereditor.syntax.MissingSemicolon;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Syntax;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.widget.CodeView;
import de.markusfisch.android.shadereditor.widget.GoToSheet;
import de.markusfisch.android.shadereditor.widget.NameView;
import de.markusfisch.android.shadereditor.widget.SignatureView;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyJson;
import de.markusfisch.android.shadereditor.widget.ShaderToySource;

/**
 * The editor built on CodeView: one document, its colours and its line
 * marks, made again for every new text.
 */
final class CodeViewSourceEditor implements SourceEditor {
	// Runs the directives of an edited text, the latest asked only.
	private static final Executor AGAIN = Executors.newSingleThreadExecutor();
	// How long dimmed lines that an edit made stale may stay.
	private static final int STALE_MILLIS = 50;
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
	// What the edits put in since the last analysis.
	private final ArrayList<Text> inserted = new ArrayList<>();
	private final Document.Listener documentListener = new Document.Listener() {
		@Override
		public void onChange(Document.Change change, Text before) {
			inserted.add(change.inserted());
		}

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
	private DimmedLines dimmed;
	// The last build's run for another text, and the text asked for last.
	@Nullable
	private BuiltLines.Again again;
	private final AtomicReference<Text> asked = new AtomicReference<>();
	// Clears stale lines STALE_MILLIS after the edit that made them stale.
	private final Runnable distrust = this::distrust;
	private boolean distrusting;
	private int revision;
	// The next change starts no run: its maker runs it.
	private boolean skipRun;
	// The number the strip offers to tune, or null.
	@Nullable
	private String tunable;
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
	// The name the strip showed last with its action found, and for
	// which revision of the text and caret.
	@Nullable
	private NameView.Name named;
	private int namedRevision = -1;
	private int namedStart = -1;
	// What Definition reads of a folder at most: beyond, it cannot tell.
	private static final int TEXTS_MOST = 500;
	private static final long TEXTS_BYTES = 4 << 20;
	private Knowledge.Scope scope = Knowledge.Scope.NONE;
	@NonNull
	private List<String> outside = Collections.emptyList();
	@NonNull
	private List<String> extraWords = BUILTIN_UNIFORMS;
	// The call the strip shows, or null.
	@Nullable
	private SignatureView.Signature signature;
	// The fixes of the error row, where docs are.
	@Nullable
	private final FileFixes fixes;
	// Where docs are.
	@Nullable
	private final WordUses uses;
	// The files a file of a folder names, or null.
	@Nullable
	private FileLinks links;
	// What was offered last in a line naming files, for which revision
	// and caret, or null.
	@Nullable
	private HostCompletion.Offer hostOffer;
	private int hostRevision;
	private int hostCaret;
	// Whether the caret was in a path of a line naming files.
	private boolean inPath;
	// The revision and caret complete saw last, and whether the last
	// update between them was an edit: while the caret only moves, a
	// path it rests in is shown, while typing it is completed.
	private int seenRevision = -1;
	private int seenCaret = -1;
	private boolean typing;
	// Where in its line the next jump puts the caret, or -1.
	private int landing = -1;

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
				// Nothing while the analysis is of an older text: its
				// offsets are not this text's.
				@NonNull
				@Override
				public Knowledge declared() {
					Analysis analysis = view.getAnalysis();
					return analysis.revision() == document.revision()
							? analysis.knowledge()
							: Knowledge.NONE;
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
				public int revision() {
					return document.revision();
				}

				@NonNull
				@Override
				public String file() {
					return links != null ? links.file().toString() : "";
				}

				@Override
				public void texts(@NonNull FileTexts.Callback<
						Map<String, Text>> done) {
					FileLinks asked = links;
					Text shown = document.text();
					if (asked == null) {
						done.done(Map.of("", shown));
						return;
					}
					asked.folderTexts(TEXTS_MOST, TEXTS_BYTES, read -> {
						Map<String, Text> texts = new HashMap<>();
						for (Map.Entry<ProjectPath, Text> e :
								read.texts().entrySet()) {
							texts.put(e.getKey().toString(), e.getValue());
						}
						// The text the offsets asked about are of.
						texts.put(asked.file().toString(), shown);
						done.done(read.unread() > 0 ? null : texts);
					});
				}

				@Override
				public void goTo(@NonNull String file, int start, int line,
						@NonNull String word, @NonNull Runnable then) {
					goToName(file, start, line, word, then);
				}

				// The call the caret is in can be shown now.
				@Override
				public void onDocsRead() {
					complete();
				}

				@Override
				public void addUniforms(@NonNull List<String> statements) {
					addMarked(statements);
				}

				@Override
				public boolean offersUses() {
					return uses.offered();
				}

				@Override
				public void uses(@NonNull String word, @Nullable String file,
						boolean keyboard, boolean renamable, boolean rename) {
					uses.show(word, file, keyboard, renamable, rename);
				}
			});
			uses = new WordUses(view, new WordUses.Host() {
				@Nullable
				@Override
				public FileLinks links() {
					return links;
				}

				@NonNull
				@Override
				public Document document() {
					return document;
				}

				@Override
				public void jump(@Nullable String file, int line,
						@NonNull Runnable back) {
					CodeViewSourceEditor.this.jump(file, line, back);
				}

				@Override
				public void refocus(boolean keyboard) {
					docs.refocus(keyboard);
				}

				// As a pause in typing does, now.
				@Override
				public void run() {
					view.removeCallbacks(editPaused);
					editPaused();
				}
			});
			fixes = new FileFixes(view.getContext(), new FileFixes.Host() {
				@Nullable
				@Override
				public FileLinks links() {
					return links;
				}

				@NonNull
				@Override
				public Document document() {
					return document;
				}
			}, new SemicolonFix(docs));
		} else {
			docs = null;
			fixes = null;
			uses = null;
		}
		show(new Document(Text.EMPTY), DATABASE);
	}

	@NonNull
	@Override
	public View getView() {
		return view;
	}

	@Override
	public int getLineHeight() {
		return view.getLineHeight();
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
		inserted.add(document.text());
		if (listener != null) {
			listener.onTextModified();
			listener.onEditPaused(pausedText());
		}
	}

	@Override
	public boolean showDocument(@NonNull Document document,
			@NonNull Syntax syntax, @NonNull Knowledge.Scope scope,
			@Nullable Object state, boolean shaderToy) {
		show(document, syntax);
		this.scope = scope;
		if (uses != null) {
			uses.shown(document);
		}
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
		inserted.add(document.text());
		view.bringCaretIntoView();
	}

	@Override
	public void insert(@NonNull CharSequence text) {
		if (view.holds(() -> insert(text))) {
			return;
		}
		Document.Selection selection = document.selection();
		document.replace(selection.start(), selection.end(), Text.of(text));
		view.bringCaretIntoView();
	}

	@Override
	public void complete(@NonNull String word, int typed) {
		if (view.holds(() -> complete(word, typed))) {
			return;
		}
		HostCompletion.Offer offer = hostOffer;
		HostCompletion.Choice choice = offer != null &&
				hostRevision == document.revision() &&
				hostCaret == document.selection().head() &&
				document.selection().isEmpty()
				? offer.choice(word)
				: null;
		if (choice == null) {
			completeWord(word, typed);
			return;
		}
		int lineStart = document.text().lineStart(
				document.text().lineOf(hostCaret));
		document.beginBatch();
		document.replace(lineStart + choice.start(),
				lineStart + choice.end(), Text.of(choice.text()));
		document.select(lineStart + choice.caret(),
				lineStart + choice.caret());
		document.endBatch();
		view.bringCaretIntoView();
	}

	// The word replaces what was typed of it, so that a screen reader hears
	// the word and not only its rest.
	private void completeWord(@NonNull String word, int typed) {
		Document.Selection selection = document.selection();
		int start = selection.head() - typed;
		if (!selection.isEmpty() || typed > word.length() || start < 0 ||
				!document.text().substring(start, selection.head())
						.equals(word.substring(0, typed))) {
			SourceEditor.super.complete(word, typed);
			return;
		}
		document.replace(start, selection.head(), Text.of(word));
		view.bringCaretIntoView();
	}

	@Override
	public void setFileLinks(@Nullable FileLinks links) {
		if (this.links != null) {
			this.links.point(null);
			this.links.setListener(null);
			this.links.setJumping(null);
		}
		this.links = links;
		hostOffer = null;
		if (links != null) {
			// Going back moves the caret as a jump does.
			links.setJumping(view::jumping);
			// A folder listed may name the file a missing one is near.
			links.setListener(() -> {
				complete();
				view.fixesChanged();
			});
		}
		view.fixesChanged();
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

	// Adds statements as Add uniform does, for a fix, as one undo step:
	// marks their lines until the next edit and says where they went.
	private void addMarked(@NonNull List<String> statements) {
		int[] at = AddUniform.apply(document, statements);
		int[] lines = new int[at.length];
		List<String> added = new ArrayList<>();
		List<String> numbers = new ArrayList<>();
		for (int i = 0; i < at.length; ++i) {
			lines[i] = document.text().lineOf(at[i]);
			String statement = statements.get(i);
			added.add(statement.substring(0, statement.length() - 1));
			numbers.add(String.valueOf(lines[i] + 1));
		}
		view.markLines(lines);
		android.content.res.Resources res = view.getResources();
		String and = res.getString(R.string.a11y_list_and);
		view.announceForAccessibility(lines.length == 1
				? res.getString(R.string.a11y_added_uniform, added.get(0),
				lines[0] + 1)
				: res.getString(R.string.a11y_added_uniforms,
				WordDocs.joinAnd(added, and),
				WordDocs.joinAnd(numbers, and)));
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

	// Go to works where Look up does: in GLSL of a file of a folder.
	@Override
	public boolean canGoTo() {
		return links != null && docs != null && styles.syntax() == DATABASE;
	}

	@Override
	public void goTo() {
		if (!canGoTo()) {
			return;
		}
		Text text = document.text();
		String file = links.file().toString();
		new GoToSheet(view.getContext(), GoToSheet.outline(
				view.getAnalysis().knowledge(), text, scope,
				file.substring(0, file.lastIndexOf('/') + 1)), text,
				ShaderEditorApp.preferences.getFont(),
				row -> jump(row.file(), row.line() + 1)).show();
	}

	// Goes to line, from 1, or where the caret is for 0, of file, a path
	// of the project, or of the file shown for null, as Open does: the
	// strip's key leads back.
	private void jump(@Nullable String file, int line) {
		jump(file, line, null);
	}

	// As above; the key back runs back after leading back.
	private void jump(@Nullable String file, int line,
			@Nullable Runnable back) {
		ProjectParseResult<ProjectPath> path = file != null
				? ProjectPath.parse(file)
				: null;
		if (links != null && (path == null || path.isSuccess())) {
			links.open(path != null ? path.value() : links.file(),
					Math.max(0, line), back);
		} else if (file == null) {
			navigateToLine(line);
		} else if (listener != null) {
			listener.onOpenFile(file, line);
		}
	}

	// Goes to word at start of file, a path or this file, as Open
	// does, the caret on the name: in this file after the jump, which
	// leaves from where the caret was; in another before it, once its
	// text is read, if it still has the name there, else to line.
	private void goToName(@NonNull String file, int start, int line,
			@NonNull String word, @NonNull Runnable then) {
		FileLinks asked = links;
		if (asked == null || file.equals(asked.file().toString())) {
			Text text = document.text();
			// The caret lands on the name, so a screen reader hears the
			// landing once.
			if (start + word.length() <= text.length() && text.substring(
					start, start + word.length()).equals(word)) {
				landing = start;
			}
			jump(null, text.lineOf(Math.min(start, text.length())) + 1);
			then.run();
			return;
		}
		ProjectParseResult<ProjectPath> path = ProjectPath.parse(file);
		if (!path.isSuccess()) {
			jump(file, line + 1);
			then.run();
			return;
		}
		asked.load(List.of(path.value()), problem -> {
			if (asked != links) {
				return;
			}
			Document there = asked.document(path.value());
			jump(file, there != null && selectName(there, start, word)
					? there.text().lineOf(start) + 1
					: line + 1);
			then.run();
		});
	}

	// Puts the caret of document on word at start, if word still stands
	// there; returns whether it does.
	private static boolean selectName(@NonNull Document document, int start,
			@NonNull String word) {
		Text text = document.text();
		boolean there = start + word.length() <= text.length() &&
				text.substring(start, start + word.length()).equals(word);
		if (there) {
			document.select(start, start);
		}
		return there;
	}


	@Override
	public void navigateToLine(int line) {
		Text text = document.text();
		int at = landing;
		landing = -1;
		if (line < 1 || line > text.lineCount()) {
			return;
		}
		int start = text.lineStart(line - 1);
		int end = start + text.line(line - 1).length();
		Document.Selection selection = document.selection();
		view.jumping();
		if (at >= start && at <= end) {
			document.select(at, at);
		} else if (selection.start() < start || selection.end() > end) {
			document.select(start, start);
		}
		view.jumped();
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
	public void setBuilt(@Nullable List<Integer> off, @Nullable Text text,
			@Nullable BuiltLines.Again again) {
		boolean set = off != null && text != null && dimmed.set(text, off);
		if (!set) {
			dimmed.clear();
		}
		this.again = set ? again : null;
		trust();
		view.invalidate();
	}

	private void distrust() {
		distrusting = false;
		dimmed.clearStale();
		view.invalidate();
	}

	private void trust() {
		distrusting = false;
		view.removeCallbacks(distrust);
	}

	// Runs the directives of the text in the background; the answer counts
	// only for the same text.
	private void stale() {
		BuiltLines.Again run = again;
		DimmedLines lines = dimmed;
		// Another file the build read may have changed unheard.
		if (run == null || !run.holds()) {
			lines.clear();
			return;
		}
		Text text = document.text();
		asked.set(text);
		if (!distrusting) {
			distrusting = true;
			view.postDelayed(distrust, STALE_MILLIS);
		}
		AGAIN.execute(() -> {
			if (asked.get() != text) {
				return;
			}
			List<Integer> off = run.off(text.toString());
			view.post(() -> {
				if (again == run && dimmed == lines &&
						lines.answer(text, off)) {
					trust();
					view.invalidate();
				}
			});
		});
	}

	@NonNull
	@Override
	public List<CodeView.Fix> fixes(@NonNull ProjectDiagnostic error) {
		int line = marks.lineOf(error.line() - 1, error.message());
		return line >= 0 ? view.fixesOf(line) : List.of();
	}

	@Override
	public void applyFix(@NonNull ProjectDiagnostic error, int index) {
		int line = marks.lineOf(error.line() - 1, error.message());
		if (line >= 0) {
			view.applyFixOf(line, index);
		}
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
		if (view.holds(this::undo)) {
			return;
		}
		view.tell(R.string.a11y_undone, document::undo);
		view.bringCaretIntoView();
	}

	@Override
	public boolean canRedo() {
		return document.canRedo();
	}

	@Override
	public void redo() {
		if (view.holds(this::redo)) {
			return;
		}
		view.tell(R.string.a11y_redone, document::redo);
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
		inserted.clear();
		revision = document.revision();
		seenRevision = -1;
		typing = false;
		errors = Collections.emptyList();
		// The name of the file before is not under the caret.
		if (listener != null) {
			listener.onName(null);
		}
		styles = new LineStyles(document, syntax, STYLE_BUDGET);
		// GLSL's words, in GLSL only.
		view.setLookUp(syntax == DATABASE ? docs : null);
		view.setNames(syntax == DATABASE && docs != null ? names : null);
		view.setLineLink(syntax == DATABASE ? lineLink : null);
		marks = new LineMarks(document);
		again = null;
		trust();
		dimmed = new DimmedLines(document, this::stale);
		styles.setDimmed(dimmed);
		view.setDocument(document, styles, marks);
		view.setRowFix(syntax == DATABASE ? fixes : null);
		document.addListener(documentListener);
		// A file's document keeps its caret: a tap where it already is
		// moves nothing, so its number is offered now.
		offerTuning(false);
		// JSON that is there when a file is opened is not put in.
		toldJson = beginsAsJson();
	}

	private void release() {
		document.removeListener(documentListener);
		styles.close();
		marks.close();
		dimmed.close();
	}

	private void updated() {
		boolean edited = document.revision() != revision;
		// The number to tune first: the strip shows it, not the call.
		offerTuning(edited);
		complete();
		if (!edited) {
			return;
		}
		revision = document.revision();
		if (listener != null) {
			listener.onTextModified();
		}
		if (skipRun) {
			skipRun = false;
			return;
		}
		view.removeCallbacks(editPaused);
		view.postDelayed(editPaused, updateDelay);
	}

	@Override
	public void skipNextRun() {
		skipRun = true;
	}

	@Override
	public int lineState(int line) {
		return styles != null && styles.syntax() == DATABASE &&
				line < document.text().lineCount()
				? styles.state(line)
				: -1;
	}

	// The strip offers to tune the number the caret was moved onto, or
	// that an edit other than typing left it on, such as Undo or the
	// bar's Done, by the tokens of the caret's line only; typing offers
	// nothing.
	private void offerTuning(boolean edited) {
		String number = null;
		Document.Selection selection = document.selection();
		if (!(edited && document.isTyping()) && docs != null &&
				styles.syntax() == DATABASE &&
				selection.isEmpty()) {
			Text text = document.text();
			int caret = selection.head();
			int n = text.lineOf(caret);
			LexedLine line = styles.runs(n).line();
			int column = caret - text.lineStart(n);
			boolean onNumber = false;
			for (int i = 0, size = line.size(); i < size; ++i) {
				onNumber |= line.style(i) == Style.NUMBER &&
						line.start(i) <= column && column <= line.end(i);
			}
			// The number as the bar tunes it, with the minus that
			// belongs to it, from the caret's line alone; on a directive
			// line only a #define's, not one the host reads, such as
			// #pragma se.
			de.markusfisch.android.shadereditor.tuning.TunedPlace place =
					onNumber
							? de.markusfisch.android.shadereditor.tuning
							.TunedPlace.at(line.text(), column)
							: null;
			number = place != null ? place.literalWithSign() : null;
		}
		if (listener != null && !java.util.Objects.equals(number, tunable)) {
			tunable = number;
			listener.onTunable(number);
		}
	}

	private void analysed(@NonNull Analysis analysis) {
		complete();
		if (convert && isShaderToySource(analysis.knowledge())) {
			String source = getText();
			// Only if an edit put its mainImage() in (#122), and not
			// again after the conversion was undone.
			String converted = source.equals(converting)
					? null
					: ShaderToySource.convert(document, source, inserted);
			if (converted != null) {
				converting = source;
				view.bringCaretIntoView();
			}
		}
		// Used up also when nothing was converted.
		inserted.clear();
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
			point(null);
			listener.onName(null);
			// The keys, whose Tab indents the lines selected.
			if (docs != null && styles.syntax() == DATABASE) {
				listener.onCompletions(GlslCompletion.KEYS, 0);
			}
			return;
		}
		Text text = document.text();
		int caret = selection.head();
		if (document.revision() != seenRevision) {
			typing = seenRevision >= 0;
		} else if (caret != seenCaret) {
			typing = false;
		}
		seenRevision = document.revision();
		seenCaret = caret;
		int n = text.lineOf(caret);
		int lineStart = text.lineStart(n);
		Analysis analysis = view.getAnalysis();
		Knowledge known = analysis.knowledge();
		LexedLine line = styles.runs(n).line();
		int column = caret - lineStart;
		if (completeHostLine(line, column, caret)) {
			listener.onName(null);
			return;
		}
		NameView.Name name = !typing && analysis.revision() ==
				document.revision() ? name(line, column, lineStart) : null;
		listener.onName(name);
		if (name != null) {
			signature(-1, 0);
			return;
		}
		// Functions with their parenthesis, unless one follows already.
		boolean parentheses = docs != null && styles.syntax() == DATABASE &&
				(column == line.text().length() ||
						line.text().charAt(column) != '(');
		styles.syntax().complete(line, column,
				lineStart, known,
				analysis.revision() == document.revision(), extraWords(),
				scope, (words, position) -> {
					// The call shows behind the keys, not behind words,
					// nor on a number, which offers to tune it instead:
					// one thing at the end of the strip.
					signature(words == GlslCompletion.KEYS && tunable == null
							? n : -1, column);
					// What stands behind a "." is a member, no call.
					int before = column - position - 1;
					// The keys go as they are: the strip knows them.
					listener.onCompletions(words != GlslCompletion.KEYS &&
							parentheses && (before < 0 ||
							line.text().charAt(before) != '.')
							? withParentheses(words, known)
							: words, position);
				});
	}

	// The name the caret at column of line, which starts at lineStart,
	// rests in, as the strip shows it, or null: in GLSL where there are
	// docs, for an identifier, a built-in function or variable; not for
	// a field, behind a "." or where its struct declares it, nor a
	// qualifier in "layout(...)", nor in a directive but #define.
	// Lookups only, as on every caret move; its action comes later.
	@Nullable
	private NameView.Name name(@NonNull LexedLine line, int column,
			int lineStart) {
		String word = nameAt(line, column, lineStart);
		if (word == null) {
			return null;
		}
		// Its action found for this text and this word, wherever the
		// caret is in it, is kept, so that the strip is not made again
		// while they stay.
		int revision = document.revision();
		int start = column;
		while (start > 0 && isWordChar(line.text().charAt(start - 1))) {
			--start;
		}
		int at = lineStart + start;
		NameView.Name kept = named;
		if (kept != null && namedRevision == revision &&
				namedStart == at && kept.word().equals(word)) {
			return kept;
		}
		return docs.name(word, found -> {
			named = found;
			namedRevision = revision;
			namedStart = at;
			if (listener != null) {
				listener.onName(found);
			}
		});
	}

	// The word of that name, or null.
	@Nullable
	private String nameAt(@NonNull LexedLine line, int column,
			int lineStart) {
		if (docs == null || styles.syntax() != DATABASE) {
			return null;
		}
		String text = line.text();
		int start = column;
		while (start > 0 && isWordChar(text.charAt(start - 1))) {
			--start;
		}
		int end = column;
		while (end < text.length() && isWordChar(text.charAt(end))) {
			++end;
		}
		int i = start < end ? line.indexAt(start) : -1;
		Style style = i < 0 ? null : line.style(i);
		if ((style != Style.IDENTIFIER && style != Style.TYPE &&
				style != Style.BUILTIN_FUNCTION) ||
				(line.inDirective(i) && !text.trim().startsWith("#define")) ||
				Definitions.noName(view.getAnalysis().knowledge(),
						document.text(), lineStart + start)) {
			return null;
		}
		String word = text.substring(start, end);
		Style kind = GlslWords.style(word, 0, word.length());
		return kind == Style.IDENTIFIER || kind == Style.BUILTIN_FUNCTION
				? word
				: null;
	}

	// The name the caret rests in, typed or not, for keys and TalkBack;
	// none while the analysis is of an older text, as for the strip.
	private final CodeView.Names names = new CodeView.Names() {
		@Nullable
		@Override
		public String name() {
			Document.Selection selection = document.selection();
			if (!selection.isEmpty() ||
					view.getAnalysis().revision() != document.revision()) {
				return null;
			}
			Text text = document.text();
			int n = text.lineOf(selection.head());
			int lineStart = text.lineStart(n);
			return nameAt(styles.runs(n).line(), selection.head() - lineStart,
					lineStart);
		}

		// On a path, F12 opens its file, as the strip's Open does.
		@Override
		public void definition() {
			String word = name();
			if (word != null) {
				docs.definitionKey(word);
				return;
			}
			Text text = document.text();
			int caret = document.selection().head();
			int n = text.lineOf(caret);
			HostCompletion.Named named = links != null
					? HostCompletion.named(text.line(n),
					caret - text.lineStart(n), links.file(), links)
					: null;
			if (named != null && (named.kind() == HostCompletion.Kind.INCLUDE ||
					FileKind.hint(named.path().getFileName(), null) !=
							FileKind.PICTURE)) {
				links.open(named.path(), 0);
			}
		}

		@Override
		public void uses(boolean rename) {
			String word = name();
			if (word != null) {
				docs.uses(word, rename);
			}
		}

		@Override
		public void lookUp() {
			String word = name();
			if (word != null) {
				docs.lookUp(word);
			}
		}

		@Override
		public boolean listsUses() {
			String word = name();
			return word != null && docs.listsUses(word);
		}

		@Override
		public boolean renames() {
			String word = name();
			return word != null && docs.renames(word);
		}

		@Override
		public boolean back() {
			return links != null && links.back();
		}

		@Nullable
		@Override
		public String wayBack() {
			return links != null ? links.wayBack() : null;
		}
	};

	private static boolean isWordChar(char c) {
		return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
				(c >= '0' && c <= '9');
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
				// The form by the types of the arguments before the
				// caret: read from the tokens the call was found in.
				Analysis analysis = view.getAnalysis();
				found = docs.signature(call.name(), call.argument(),
						styles.syntax().argumentTypes(
								n -> styles.runs(n).line(), call, line,
								column, document.text().lineStart(
										call.line()),
								analysis.knowledge(), analysis.revision() ==
										document.revision(), scope));
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

	// Offers what a line naming files takes at the caret, from its text
	// and the listings links has; returns whether it is such a line.
	private boolean completeHostLine(@NonNull LexedLine line, int column,
			int caret) {
		hostOffer = null;
		String text = line.text();
		enter(text, column);
		int hash = text.indexOf('#');
		int index = hash >= 0 ? line.indexAt(hash) : -1;
		// HostCompletion.owns is the rule between these lines and the
		// language; where it holds, no call shows behind the keys.
		if (links == null || styles.syntax() != DATABASE ||
				!HostCompletion.owns(text, column,
						index >= 0 && line.inDirective(index))) {
			point(null);
			return false;
		}
		signature(-1, 0);
		HostCompletion.Named named = typing
				? null
				: HostCompletion.named(text, column, links.file(), links);
		point(named);
		if (named != null) {
			return true;
		}
		HostCompletion.Offer offer = HostCompletion.offer(text, column,
				links.file(), links, links.drawsHalfFloat());
		if (offer.choices().isEmpty()) {
			// A name typed whole is shown as if the caret rested in it.
			HostCompletion.Named whole = HostCompletion.named(text, column,
					links.file(), links);
			if (whole != null) {
				point(whole);
				return true;
			}
		}
		hostOffer = offer;
		hostRevision = document.revision();
		hostCaret = caret;
		listener.onCompletions(offer.words(), offer.typed());
		return true;
	}

	private final CodeView.LineLink lineLink = new CodeView.LineLink() {
		@Nullable
		@Override
		public String name(int line) {
			HostCompletion.Named named = namedOn(line);
			return named != null ? named.path().getFileName() : null;
		}

		@Override
		public void open(int line) {
			HostCompletion.Named named = namedOn(line);
			if (named != null) {
				links.open(named.path(), 0);
			}
		}
	};

	// The file the path of line names, as the strip shows it with the caret
	// in the path; a picture is not opened from a line there either.
	@Nullable
	private HostCompletion.Named namedOn(int line) {
		if (links == null || document == null) {
			return null;
		}
		HostCompletion.Named named = HostCompletion.namedOn(
				document.text().line(line), links.file(), links);
		return named != null && (named.kind() == HostCompletion.Kind.INCLUDE ||
				FileKind.hint(named.path().getFileName(), null) !=
						FileKind.PICTURE)
				? named
				: null;
	}

	// Tells links which file the caret rests in the path of, if any.
	private void point(@Nullable HostCompletion.Named named) {
		if (links != null) {
			links.point(named);
		}
	}

	// Lets links list again when the caret enters a path, once.
	private void enter(@NonNull String text, int column) {
		boolean path = links != null &&
				HostCompletion.pathAt(text, column) != null;
		if (path && !inPath) {
			links.refresh();
		}
		inPath = path;
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
			listener.onEditPaused(pausedText());
		}
	}

	// The text as it is now, copied only when it is read: where files
	// run, a pause needs no copy of it.
	@NonNull
	private CharSequence pausedText() {
		Text text = document.text();
		return new CharSequence() {
			@Override
			public int length() {
				return text.length();
			}

			@Override
			public char charAt(int index) {
				return text.substring(index, index + 1).charAt(0);
			}

			@NonNull
			@Override
			public CharSequence subSequence(int start, int end) {
				return text.substring(start, end);
			}

			@NonNull
			@Override
			public String toString() {
				return text.toString();
			}
		};
	}

	// Insert ';' where a build showed it missing, then the other fixes.
	private final class SemicolonFix implements CodeView.RowFix {
		private final CodeView.RowFix other;

		SemicolonFix(CodeView.RowFix other) {
			this.other = other;
		}

		@NonNull
		@Override
		public List<CodeView.Fix> fixes(int line,
				@NonNull List<String> messages) {
			List<CodeView.Fix> fixes = new ArrayList<>(
					other.fixes(line, messages));
			if (marks.hintFor(line) >= 0) {
				fixes.add(0, CodeView.Fix.of(view.getContext().getString(
						R.string.fix_insert_semicolon)));
			}
			return fixes;
		}

		@Override
		public void apply(int line, @NonNull List<String> messages,
				int index) {
			int at = semicolonAt(line, messages);
			if (at >= 0 && index-- == 0) {
				document.replace(at, at, Text.of(";"));
			} else {
				other.apply(line, messages, index);
			}
		}
	}

	// Where the build showed a ';' missing for the error on line, while
	// that line is as built; else -1, and the fix does nothing until the
	// next build. Diagnostics have no column.
	private int semicolonAt(int line, @NonNull List<String> messages) {
		int missing = marks.hintFor(line);
		if (missing < 0 || marks.hintChanged()) {
			return -1;
		}
		Text text = document.text();
		int start = text.lineStart(missing);
		int at = MissingSemicolon.place(text.substring(start,
						text.lineStart(line) + text.line(line).length()),
				line - missing, messages.get(0));
		return at >= 0 && at <= text.line(missing).length()
				? start + at
				: -1;
	}
}
