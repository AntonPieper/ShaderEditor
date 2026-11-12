package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Layout;
import android.text.Spannable;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.ReplacementSpan;
import android.text.style.TabStopSpan;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.highlighter.EditedRange;
import de.markusfisch.android.shadereditor.highlighter.Highlight;
import de.markusfisch.android.shadereditor.highlighter.TokenDiff;
import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.syntax.GlslCompletion;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

public class ShaderEditor extends LineNumberEditText {
	private static final Pattern PATTERN_TRAILING_WHITE_SPACE = Pattern.compile(
			"[\\t ]+$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_INSERT_UNIFORM = Pattern.compile(
			"^([ \t]*uniform.+)$",
			Pattern.MULTILINE);
	private static final Pattern PATTERN_ENDIF = Pattern.compile(
			"(#endif)\\b");
	private static final Pattern PATTERN_NO_BREAK_SPACE = Pattern.compile(
			"\\xA0");
	private static final ArrayList<String> DEFAULT_COMPLETIONS = new ArrayList<>();
	private static final Tokens NO_TOKENS = GlslLexer.lex("");
	private static final Analysis NO_ANALYSIS = new Analysis(NO_TOKENS,
			Outline.of(NO_TOKENS, ""));
	private static final List<String> BUILTIN_UNIFORMS = new ArrayList<>();

	static {
		DEFAULT_COMPLETIONS.add("{");
		DEFAULT_COMPLETIONS.add("}");
		DEFAULT_COMPLETIONS.add("(");
		DEFAULT_COMPLETIONS.add(")");
		DEFAULT_COMPLETIONS.add("[");
		DEFAULT_COMPLETIONS.add("]");
		for (UniformDef uniform : UniformTable.BUILTINS) {
			BUILTIN_UNIFORMS.add(uniform.getName());
		}
		Collections.sort(BUILTIN_UNIFORMS);
	}

	private int revision = 0;
	private final int[] colors = new int[Style.values().length];
	private final TokenListUpdater tokenListUpdater = new TokenListUpdater(
			(tokenRevision, analysis, text) -> post(() -> {
				if (tokenRevision == revision) {
					provideCompletions(analysis, text);
				}
			}));
	private final Runnable updateRunnable = new Runnable() {
		@Override
		public void run() {
			Editable e = getText();

			if (e != null) {
				if (onEditPausedListener != null) {
					onEditPausedListener.onEditPaused(e.toString());
				}

				highlightWithoutChange(e);
			}

		}
	};

	@Nullable
	private OnTextModifiedListener onTextModifiedListener;
	@Nullable
	private OnEditPausedListener onEditPausedListener;
	@Nullable
	private CodeCompletionListener codeCompletionListener;
	private int updateDelay = 1000;
	@NonNull
	private List<ProjectDiagnostic> shaderErrors = Collections.emptyList();
	private boolean isUserInteraction = true;
	private int colorError;
	private int colorWarning;
	private int textColor;
	private int tabWidthInCharacters = 0;
	private int tabWidth = 0;
	@NonNull
	private IndentTabStop[] tabStops = new IndentTabStop[0];
	private Tokens tokens = NO_TOKENS;
	private final EditedRange editedRange = new EditedRange();
	private boolean isApplyingEdit = false;

	public ShaderEditor(Context context) {
		super(context);
		init(context);
	}

	public ShaderEditor(
			@NonNull Context context,
			@Nullable AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	private static <T> void clearSpans(Spannable e,
			int start, int end, Class<T> clazz) {
		// Remove foreground color spans.
		T[] spans = e.getSpans(
				start,
				end,
				clazz);
		for (T span : spans) {
			e.removeSpan(span);
		}
	}

	private static String convertShaderToySource(String src) {
		if (!ShaderToySource.needsConversion(src)) {
			return null;
		}
		// Only include and translate uniforms that have an equivalent.
		return "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
				"precision highp float;\n" +
				"#else\n" +
				"precision mediump float;\n" +
				"#endif\n\n" +
				"uniform vec2 resolution;\n" +
				"uniform float time;\n" +
				"uniform vec4 mouse;\n" +
				"uniform vec4 date;\n\n" +
				src.replaceAll("iResolution", "resolution")
						.replaceAll("iGlobalTime", "time")
						.replaceAll("iMouse", "mouse")
						.replaceAll("iDate", "date") +
				"\n\nvoid main() {\n" +
				"\tvec4 fragment_color;\n" +
				"\tmainImage(fragment_color, gl_FragCoord.xy);\n" +
				"\tgl_FragColor = fragment_color;\n" +
				"}\n";
	}

	public void setOnEditPausedListener(
			@Nullable OnEditPausedListener listener) {
		onEditPausedListener = listener;
	}

	public void setOnTextModifiedListener(
			@Nullable OnTextModifiedListener listener) {
		onTextModifiedListener = listener;
	}

	public void setOnCompletionsListener(
			@Nullable CodeCompletionListener listener) {
		codeCompletionListener = listener;
	}

	public void setUpdateDelay(int ms) {
		updateDelay = ms;
	}

	public void setTabWidth(int characters) {
		if (tabWidthInCharacters == characters) {
			return;
		}

		tabWidthInCharacters = characters;
		tabWidth = Math.round(getPaint().measureText("m") * characters);

		int[] stops = IndentTabs.stops(tabWidth);
		tabStops = new IndentTabStop[stops.length];
		for (int i = 0; i < stops.length; ++i) {
			tabStops[i] = new IndentTabStop(stops[i]);
		}
		Editable e = getText();
		if (e != null) {
			convertTabs(e, 0, e.length());
		}
	}

	public boolean hasErrors() {
		return ProjectDiagnostic.anyError(shaderErrors);
	}

	public List<ProjectDiagnostic> getErrors() {
		return shaderErrors;
	}

	public void setErrors(@NonNull List<ProjectDiagnostic> errorLines) {
		this.shaderErrors = errorLines;
	}

	public void updateHighlighting() {
		Editable text = getText();
		if (text != null) {
			highlightWithoutChange(text);
		}
	}

	private void clearError(@Nullable Spannable e) {
		if (e == null) {
			return;
		}
		clearSpans(e, 0, e.length(), BackgroundColorSpan.class);
	}

	private void highlightErrors(List<ProjectDiagnostic> shaderErrors) {
		Spannable e = getText();
		clearError(e);
		if (e == null || e.length() == 0) {
			return;
		}
		for (ProjectDiagnostic shaderError : shaderErrors) {
			int line = shaderError.line() - 1;
			Layout layout = getLayout();
			if (line < 0 || line >= layout.getLineCount()) {
				continue;
			}
			e.setSpan(
					new BackgroundColorSpan(shaderError.severity() ==
							ProjectDiagnostic.Severity.WARNING
							? colorWarning
							: colorError),
					layout.getLineStart(line),
					layout.getLineEnd(line),
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	public void updateErrorHighlighting() {
		Spannable e = getText();
		clearError(e);
		if (!shaderErrors.isEmpty()) {
			highlightErrors(shaderErrors);
		}
	}

	public void setTextHighlighted(CharSequence text) {
		if (text == null) {
			text = "";
		}

		cancelUpdate();

		shaderErrors = Collections.emptyList();

		isUserInteraction = false;

		// `setText` can't be overridden
		tokenListUpdater.update(text, ++revision);
		setText(colourAll(text));
		editedRange.reset();
		isUserInteraction = true;

		Editable e = getText();
		if (e != null) {
			var str = e.toString();
			if (onTextModifiedListener != null) {
				onTextModifiedListener.onTextModified();
			}
			if (onEditPausedListener != null) {
				onEditPausedListener.onEditPaused(str);
			}
		}
	}

	public String getCleanText() {
		Editable text = getText();
		if (text == null) {
			return "";
		}
		return PATTERN_TRAILING_WHITE_SPACE
				.matcher(text)
				.replaceAll("");
	}

	public void insert(@NonNull CharSequence text) {
		int start = getSelectionStart();
		int end = getSelectionEnd();
		Editable e = getText();
		if (e == null) {
			return;
		}
		e.replace(Math.min(start, end),
				Math.max(start, end),
				text,
				0,
				text.length());
	}

	public void navigateToLine(int lineNumber) {
		// Navigate to start of line if not already there.
		if (lineNumber < 1) {
			return;
		}
		Layout layout = getLayout();
		int lineStart = layout.getLineStart(lineNumber - 1);
		int lineEnd = layout.getLineEnd(lineNumber - 1);
		if (getSelectionStart() >= lineStart &&
				getSelectionEnd() <= lineEnd) {
			return;
		}
		setSelection(lineStart, lineStart);
	}

	public void addUniform(String statement) {
		if (statement == null) {
			return;
		}

		Editable e = getText();
		if (e == null) {
			return;
		}
		removeUniform(e, statement);

		Matcher m = PATTERN_INSERT_UNIFORM.matcher(e);
		int start = -1;

		while (m.find()) {
			start = m.end();
		}

		if (start > -1) {
			// Add line break before statement because it's
			// inserted before the last line-break.
			statement = "\n" + statement;
		} else {
			// Add a line break after statement if there's no
			// uniform already.
			statement += "\n";

			// Add an empty line between the last #endif
			// and the now following uniform.
			if ((start = endIndexOfLastEndIf(e)) > -1) {
				statement = "\n" + statement;
			}

			// Move index past line break or to the start
			// of the text when no #endif was found.
			++start;
		}

		e.insert(start, statement);
	}

	/**
	 * Define functions required for AutoFill API
	 */
	@RequiresApi(api = Build.VERSION_CODES.O)
	@Override
	public int getAutofillType() {
		return AUTOFILL_TYPE_NONE;
	}

	@Nullable
	@Override
	public InputConnection onCreateInputConnection(
			@NonNull EditorInfo outAttrs) {
		InputConnection connection = super.onCreateInputConnection(outAttrs);
		if (ShaderEditorApp.preferences.hideNativeSuggestions()) {
			outAttrs.inputType = InputType.TYPE_NULL;
		}
		return connection;
	}

	@Override
	protected void onSelectionChanged(int selStart, int selEnd) {
		super.onSelectionChanged(selStart, selEnd);
		Editable text = getText();
		Analysis completed;
		if (text != null &&
				codeCompletionListener != null &&
				getSelectionStart() == getSelectionEnd() &&
				!isApplyingEdit &&
				(completed = tokenListUpdater.getCompleted(revision)) != null) {
			provideCompletions(completed, text);
		}
	}

	private void removeUniform(Editable e, String statement) {
		if (statement == null) {
			return;
		}

		String regex = "^(" + statement.replace(" ", "[ \\t]+");
		int p = regex.indexOf(";");
		if (p > -1) {
			regex = regex.substring(0, p);
		}
		regex += ".*\\n)$";

		Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(e);
		if (m.find()) {
			e.delete(m.start(), m.end());
		}
	}

	private int endIndexOfLastEndIf(Editable e) {
		Matcher m = PATTERN_ENDIF.matcher(e);
		int idx = -1;

		while (m.find()) {
			idx = m.end();
		}

		return idx;
	}

	private void init(@NonNull Context context) {
		// Setting this through XML does not work
		setHorizontallyScrolling(true);

		setFilters(new InputFilter[]{(source,
				start, end,
				dest, dstart, dend) -> {
			if (isUserInteraction &&
					end - start == 1 &&
					start < source.length() &&
					dstart < dest.length()) {
				char c = source.charAt(start);

				if (c == '\n') {
					return autoIndent(source, dest, dstart, dend);
				}
			}

			return source;
		}});

		addTextChangedListener(new TextWatcher() {
			private int start = 0;
			private int count = 0;

			@Override
			public void onTextChanged(
					CharSequence s,
					int start,
					int before,
					int count) {
				this.start = start;
				this.count = count;
				editedRange.replace(start, before, count);
			}

			@Override
			public void beforeTextChanged(
					CharSequence s,
					int start,
					int count,
					int after) {
				isApplyingEdit = true;
			}

			@Override
			public void afterTextChanged(Editable e) {
				cancelUpdate();
				convertTabs(e, start, count);

				String converted = convertShaderToySource(e.toString());
				if (converted != null) {
					// This replaces the text: e is stale from here on,
					// and the new text is already tokenized and announced.
					setTextHighlighted(converted);
					isApplyingEdit = false;
					return;
				}

				if (!isUserInteraction) {
					isApplyingEdit = false;
					return;
				}

				if (onTextModifiedListener != null) {
					onTextModifiedListener.onTextModified();
				}

				isApplyingEdit = false;
				tokenListUpdater.update(e, ++revision);
				postDelayed(updateRunnable, updateDelay);
			}
		});

		setSyntaxColors(context);
		setUpdateDelay(ShaderEditorApp.preferences.getUpdateDelay());
		setTabWidth(ShaderEditorApp.preferences.getTabWidth());

		setOnKeyListener((v, keyCode, event) -> {
			if (ShaderEditorApp.preferences.useTabForIndent() &&
					event.getAction() == KeyEvent.ACTION_DOWN &&
					keyCode == KeyEvent.KEYCODE_TAB) {
				// Insert a tab character instead of doing focus
				// navigation.
				insert("\t");
				return true;
			}
			return false;
		});
	}

	private void setSyntaxColors(Context context) {
		for (Style style : Style.values()) {
			colors[style.ordinal()] =
					ContextCompat.getColor(context, Highlight.colorOf(style));
		}
		textColor = ContextCompat.getColor(context, R.color.editor_text);
		colorError = ContextCompat.getColor(
				context,
				R.color.syntax_error);
		colorWarning = ContextCompat.getColor(
				context,
				R.color.syntax_warning);
	}

	private void cancelUpdate() {
		removeCallbacks(updateRunnable);
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		cancelUpdate();
		tokenListUpdater.shutdown();
	}

	private void highlightWithoutChange(@NonNull Editable e) {
		isUserInteraction = false;
		highlight(e);
		isUserInteraction = true;
	}

	/**
	 * @return the text for setText() with a colour span for every token
	 * and the tab stops, which convertTabs() then finds in place
	 */
	private CharSequence colourAll(@NonNull CharSequence text) {
		// When pasting text from other apps, e.g. Github Mobile code
		// viewer, the text can be tainted with No-Break Space (U+00A0)
		// characters.
		String plain = PATTERN_NO_BREAK_SPACE.matcher(text).replaceAll(" ");
		int length = plain.length();
		if (length == 0) {
			tokens = NO_TOKENS;
			return plain;
		}
		ColouredText coloured = new ColouredText(plain, 0);
		for (IndentTabStop stop : tabStops) {
			coloured.setSpan(stop, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
		}
		if (ShaderEditorApp.preferences.disableHighlighting() &&
				length > 4096) {
			return coloured;
		}
		Tokens newTokens = tokenListUpdater.ensureUpdated(plain, revision).tokens();
		for (int i = 0, size = newTokens.size(); i < size; ++i) {
			@ColorInt int color = colors[newTokens.style(i).ordinal()];
			if (color != textColor) {
				coloured.setSpan(
						new ForegroundColorSpan(color),
						newTokens.start(i), newTokens.end(i),
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			}
		}
		tokens = newTokens;
		return coloured;
	}

	private void highlight(@NonNull Editable e) {
		int length = e.length();

		clearError(e);

		if (length == 0) {
			tokens = NO_TOKENS;
			editedRange.reset();
			return;
		}

		// When pasting text from other apps, e.g. Github Mobile code
		// viewer, the text can be tainted with No-Break Space (U+00A0)
		// characters.
		for (Matcher m = PATTERN_NO_BREAK_SPACE.matcher(e); m.find(); ) {
			e.replace(m.start(), m.end(), " ");
		}

		if (ShaderEditorApp.preferences.disableHighlighting() &&
				length > 4096) {
			clearSpans(e, 0, length, ForegroundColorSpan.class);
			return;
		}
		Tokens newTokens = tokenListUpdater.ensureUpdated(e, revision).tokens();

		EditedRange.Restyle restyle = editedRange.restyle(newTokens,
				TokenDiff.of(tokens, newTokens));
		if (restyle.start < restyle.end) {
			clearSpans(e, restyle.start, restyle.end, ForegroundColorSpan.class);
		}
		for (int i = restyle.firstToken; i < restyle.endToken; ++i) {
			e.setSpan(
					new ForegroundColorSpan(
							colors[newTokens.style(i).ordinal()]),
					newTokens.start(i),
					newTokens.end(i),
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}

		tokens = newTokens;
		editedRange.reset();
	}

	private void provideCompletions(
			@NonNull Analysis analysis,
			@NonNull CharSequence text) {
		CodeCompletionListener listener = codeCompletionListener;
		if (listener == null) {
			return;
		}
		Tokens tokens = analysis.tokens();
		int start = getSelectionStart();
		int index = GlslCompletion.tokenBefore(tokens, start);
		if (index < 0) {
			listener.onCodeCompletions(DEFAULT_COMPLETIONS, 0);
			return;
		}
		if (GlslCompletion.isInComment(tokens, text, index, start)) {
			listener.onCodeCompletions(Collections.emptyList(), 0);
			return;
		}
		int positionInToken = start - tokens.start(index);
		List<String> completions = GlslCompletion.words(
				text.subSequence(tokens.start(index), start).toString(),
				tokens.inDirective(index),
				analysis.outline(),
				tokens.start(index),
				BUILTIN_UNIFORMS);

		if (completions.isEmpty()) {
			completions = DEFAULT_COMPLETIONS;
			positionInToken = 0;
		}
		if (completions.size() == 1 && completions.get(0).contentEquals(
				text.subSequence(tokens.start(index), start))) {
			completions = DEFAULT_COMPLETIONS;
			positionInToken = 0;
		}
		listener.onCodeCompletions(
				completions,
				positionInToken);
	}

	private CharSequence autoIndent(
			CharSequence source,
			Spanned dest,
			int dstart,
			int dend) {
		String indent = "";
		int istart = dstart - 1;

		// Find start of this line.
		boolean dataBefore = false;
		int pt = 0;

		for (; istart > -1; --istart) {
			char c = dest.charAt(istart);

			if (c == '\n') {
				break;
			}

			if (c != ' ' && c != '\t') {
				if (!dataBefore) {
					// Indent always after those characters.
					if (c == '{' ||
							c == '+' ||
							c == '-' ||
							c == '*' ||
							c == '/' ||
							c == '%' ||
							c == '^' ||
							c == '=') {
						--pt;
					}

					dataBefore = true;
				}

				// Parenthesis counter.
				if (c == '(') {
					--pt;
				} else if (c == ')') {
					++pt;
				}
			}
		}

		// Copy indent of this line into the next.
		if (istart > -1) {
			char charAtCursor = dest.charAt(dstart);
			int iend;

			for (iend = ++istart; iend < dend; ++iend) {
				char c = dest.charAt(iend);

				// Auto expand comments.
				if (charAtCursor != '\n' &&
						c == '/' &&
						iend + 1 < dend &&
						dest.charAt(iend) == c) {
					iend += 2;
					break;
				}

				if (c != ' ' && c != '\t') {
					break;
				}
			}

			indent += dest.subSequence(istart, iend);
		}

		// Add new indent.
		if (pt < 0) {
			indent += "\t";
		}

		// Append white space of previous line and new indent.
		return source + indent;
	}

	/**
	 * Makes every tab in the lines of {@code [start, start + count]} one
	 * tab width wide.
	 * <p>
	 * Tab stops over the whole text place the tabs of the leading
	 * indentation. Only a tab after other characters gets a
	 * {@link TabWidthSpan}: that span is a ReplacementSpan, which makes
	 * the layout re-measure its line on every draw and reflow on every
	 * edit above it, so the editor keeps as few of them as it can.
	 */
	private void convertTabs(Editable e, int start, int count) {
		int length = e.length();
		int from = IndentTabs.lineStart(e, Math.min(start, length));
		int to = IndentTabs.lineEnd(e, Math.min(start + count, length));
		applyTabStops(e, from, to);
		for (TabWidthSpan span : e.getSpans(from, to, TabWidthSpan.class)) {
			if (e.getSpanStart(span) >= from && e.getSpanEnd(span) <= to) {
				e.removeSpan(span);
			}
		}
		if (tabWidth < 1) {
			return;
		}
		IndentTabs.forEachTabWithoutStop(e, from, to, tabStops.length,
				tab -> e.setSpan(
						new TabWidthSpan(tabWidth),
						tab,
						tab + 1,
						Spannable.SPAN_EXCLUSIVE_EXCLUSIVE));
	}

	/**
	 * Makes the current tab stops, and only those, cover the whole text.
	 * They need to be set again after new text, a new tab width, or an
	 * edit that moved them or put back other stops, such as an undo that
	 * inserts a copy of the text it replaced, spans included. Such an
	 * edit is inside {@code [start, end]}.
	 */
	private void applyTabStops(Editable e, int start, int end) {
		int length = e.length();
		boolean current = true;
		for (IndentTabStop stop : tabStops) {
			if (e.getSpanStart(stop) != 0 || e.getSpanEnd(stop) != length) {
				current = false;
				break;
			}
		}
		// The current stops cover every range, so any other stop in the
		// edited range makes the count differ.
		if (current && e.getSpans(start, end, IndentTabStop.class).length ==
				tabStops.length) {
			return;
		}
		for (IndentTabStop stop : e.getSpans(0, length, IndentTabStop.class)) {
			e.removeSpan(stop);
		}
		for (IndentTabStop stop : tabStops) {
			e.setSpan(stop, 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
		}
	}

	@FunctionalInterface
	public interface OnEditPausedListener {
		void onEditPaused(@NonNull String text);
	}

	@FunctionalInterface
	public interface OnTextModifiedListener {
		void onTextModified();
	}

	@FunctionalInterface
	public interface CodeCompletionListener {
		void onCodeCompletions(@NonNull List<String> completions, int position);
	}

	@FunctionalInterface
	interface OnTokenized {
		void onTokens(int revision, @NonNull Analysis analysis, @NonNull CharSequence text);
	}

	record Analysis(@NonNull Tokens tokens, @NonNull Outline outline) {
	}

	/**
	 * One tab stop of the editor's tab width, set over the whole text.
	 */
	private static final class IndentTabStop implements TabStopSpan {
		private final int offset;

		private IndentTabStop(int offset) {
			this.offset = offset;
		}

		@Override
		public int getTabStop() {
			return offset;
		}
	}

	private static class TabWidthSpan
			extends ReplacementSpan
			implements LineHeightSpan.WithDensity {
		private final int width;

		private TabWidthSpan(int width) {
			this.width = width;
		}

		@Override
		public int getSize(
				@NonNull Paint paint,
				CharSequence text,
				int start,
				int end,
				Paint.FontMetricsInt fm) {
			return width;
		}

		@Override
		public void draw(
				@NonNull Canvas canvas,
				CharSequence text,
				int start,
				int end,
				float x,
				int top,
				int y,
				int bottom,
				@NonNull Paint paint) {
		}

		@Override
		public void chooseHeight(CharSequence text, int start, int end,
				int spanstartv, int lineHeight,
				Paint.FontMetricsInt fm, TextPaint paint) {
			paint.getFontMetricsInt(fm);
		}

		@Override
		public void chooseHeight(CharSequence text, int start, int end,
				int spanstartv, int lineHeight, Paint.FontMetricsInt fm) {
		}
	}

	static class TokenizeCalculation implements Callable<Analysis> {
		private final String text;
		private final int revision;
		@NonNull
		private final OnTokenized onTokenized;

		public TokenizeCalculation(
				int revision,
				@NonNull String text,
				@NonNull OnTokenized onTokenized) {
			this.revision = revision;
			this.text = text;
			this.onTokenized = onTokenized;
		}

		@Override
		public Analysis call() {
			Tokens tokens = GlslLexer.lex(text);
			Outline outline = Outline.of(tokens, text);
			Analysis analysis = new Analysis(
					outline.colourTypes(tokens, text), outline);
			onTokenized.onTokens(revision, analysis, text);
			return analysis;
		}
	}

	static class TokenListUpdater {
		@NonNull
		private final OnTokenized onTokenized;
		@NonNull
		private final ExecutorService executor = Executors.newSingleThreadExecutor();
		@Nullable
		private FutureTask<Analysis> task;
		@Nullable
		private Analysis completed;
		private int revision = -1;
		private int completedRevision = -1;

		public TokenListUpdater(@NonNull OnTokenized onTokenized) {
			this.onTokenized = onTokenized;
		}

		@Nullable
		public synchronized Analysis getCompleted(int revision) {
			return revision == completedRevision ? completed : null;
		}

		private synchronized void setCompleted(int revision, @NonNull Analysis analysis) {
			if (revision != this.revision) {
				return;
			}
			completedRevision = revision;
			completed = analysis;
		}

		@NonNull
		public Analysis ensureUpdated(@NonNull CharSequence text, int revision) {
			FutureTask<Analysis> futureTask;
			synchronized (this) {
				if (task == null || revision != this.revision) {
					update(text, revision);
				}
				futureTask = task;
			}
			if (futureTask == null) {
				return NO_ANALYSIS;
			}
			try {
				Analysis analysis = futureTask.get();
				setCompleted(revision, analysis);
				return analysis;
			} catch (ExecutionException | InterruptedException e) {
				throw new RuntimeException(e);
			}
		}

		public void update(@NonNull CharSequence text, int revision) {
			FutureTask<Analysis> currentTask;
			synchronized (this) {
				if (revision == this.revision && task != null) {
					return;
				}
				currentTask = task;
				this.revision = revision;
				task = new FutureTask<>(new TokenizeCalculation(
						revision,
						text.toString(),
						(tokenRevision, analysis, tokenText) -> {
							setCompleted(tokenRevision, analysis);
							onTokenized.onTokens(tokenRevision, analysis, tokenText);
						}));
			}
			if (currentTask != null) {
				currentTask.cancel(false);
			}
			executor.submit(task);
		}

		public void shutdown() {
			if (task != null) {
				task.cancel(true);
				task = null;
			}
			executor.shutdownNow();
		}
	}
}
