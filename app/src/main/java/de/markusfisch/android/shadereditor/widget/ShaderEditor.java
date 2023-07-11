package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.CharacterStyle;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.util.AttributeSet;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.content.ContextCompat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.highlighter.Highlighter;

public class ShaderEditor extends AppCompatEditText {
	public interface OnTextChangedListener {
		void onTextChanged(String text);
	}

	private static final Pattern PATTERN_LINE = Pattern.compile(".*\\n");
	private static final Pattern PATTERN_NUMBERS = Pattern.compile("\\b(\\d*[.]?\\d+)\\b");
	private static final Pattern PATTERN_PREPROCESSOR = Pattern.compile("^[\t ]*(#define|#undef|#if|#ifdef|#ifndef|#else|#elif|#endif|" + "#error|#pragma|#extension|#version|#line)\\b", Pattern.MULTILINE);
	private static final Pattern PATTERN_KEYWORDS = Pattern.compile("\\b(attribute|const|uniform|varying|break|continue|" + "do|for|while|if|else|in|out|inout|float|int|void|bool|true|false|" + "lowp|mediump|highp|precision|invariant|discard|return|mat2|mat3|" + "mat4|vec2|vec3|vec4|ivec2|ivec3|ivec4|bvec2|bvec3|bvec4|sampler2D|" + "samplerCube|struct|gl_Vertex|gl_FragCoord|gl_FragColor)\\b");
	private static final Pattern PATTERN_BUILTINS = Pattern.compile("\\b(radians|degrees|sin|cos|tan|asin|acos|atan|pow|" + "exp|log|exp2|log2|sqrt|inversesqrt|abs|sign|floor|ceil|fract|mod|" + "min|max|clamp|mix|step|smoothstep|length|distance|dot|cross|" + "normalize|faceforward|reflect|refract|matrixCompMult|lessThan|" + "lessThanEqual|greaterThan|greaterThanEqual|equal|notEqual|any|all|" + "not|dFdx|dFdy|fwidth|texture2D|texture2DProj|texture2DLod|" + "texture2DProjLod|textureCube|textureCubeLod)\\b");
	private static final Pattern PATTERN_COMMENTS = Pattern.compile("/\\*(?:.|[\\n\\r])*?\\*/|//.*");
	private static final Pattern PATTERN_TRAILING_WHITE_SPACE = Pattern.compile("[\\t ]+$", Pattern.MULTILINE);
	private static final Pattern PATTERN_INSERT_UNIFORM = Pattern.compile("^([ \t]*uniform.+)$", Pattern.MULTILINE);
	private static final Pattern PATTERN_ENDIF = Pattern.compile("(#endif)\\b");
	private static final Pattern PATTERN_SHADER_TOY = Pattern.compile(".*void\\s+mainImage\\s*\\(.*");
	private static final Pattern PATTERN_MAIN = Pattern.compile(".*void\\s+main\\s*\\(.*");
	private static final Pattern PATTERN_NO_BREAK_SPACE = Pattern.compile("[\\xA0]");

	private static final String[] highlightNames = {"attribute",
			"comment",
			"conditional",
			"constant",
			"define",
			"function.builtin",
			"function.call",
			"function.macro",
			"function",
			"keyword",
			"number",
			"operator",
			"property",
			"preproc",
			"repeat",
			"string",
			"type",
			"type.qualifier",
			"type.builtin",
			"variable",
			"variable.builtin",
			"variable.parameter",
	};
	private @Nullable Highlighter highlighter;
	private static final int[] colors = {
			R.color.syntax_attribute,
			R.color.syntax_comment,
			R.color.syntax_conditional,
			R.color.syntax_constant,
			R.color.syntax_define,
			R.color.syntax_function_builtin,
			R.color.syntax_function_call,
			R.color.syntax_function_macro,
			R.color.syntax_function,
			R.color.syntax_keyword,
			R.color.syntax_number,
			R.color.syntax_operator,
			R.color.syntax_property,
			R.color.syntax_preproc,
			R.color.syntax_repeat,
			R.color.syntax_string,
			R.color.syntax_type,
			R.color.syntax_type_qualifier,
			R.color.syntax_type_builtin,
			R.color.syntax_variable,
			R.color.syntax_variable_builtin,
			R.color.syntax_variable_parameter,
	};

	private final Handler updateHandler = new Handler();
	private OnTextChangedListener onTextChangedListener;
	private final Runnable updateRunnable = () -> {
		Editable e = getText();

		if (e == null) return;
		if (onTextChangedListener != null) {
			onTextChangedListener.onTextChanged(e.toString());
		}

		highlightWithoutChange(e);
	};

	private int updateDelay = 1000;
	private int errorLine = 0;
	private boolean dirty = false;
	private boolean modified = true;
	private int colorError;
	private int tabWidthInCharacters = 0;
	private int tabWidth = 0;

	public ShaderEditor(Context context) {
		super(context);
		init(context);
	}

	public ShaderEditor(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	public void setOnTextChangedListener(OnTextChangedListener listener) {
		onTextChangedListener = listener;
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
	}

	public boolean hasErrorLine() {
		return errorLine > 0;
	}

	public void setErrorLine(int line) {
		errorLine = line;
	}

	public void updateHighlighting() {
		highlightWithoutChange(getText());
	}

	public boolean isModified() {
		return dirty;
	}

	public void setTextHighlighted(@Nullable CharSequence text) {
		if (text == null) {
			text = "";
		}

		cancelUpdate();

		errorLine = 0;
		dirty = false;

		modified = false;
		setText(highlight(new SpannableStringBuilder(text)), BufferType.SPANNABLE);
		modified = true;

		if (onTextChangedListener != null) {
			onTextChangedListener.onTextChanged(getText().toString());
		}
	}

	public String getCleanText() {
		return PATTERN_TRAILING_WHITE_SPACE
				.matcher(getText())
				.replaceAll("");
	}

	public void insertTab() {
		int start = getSelectionStart();
		int end = getSelectionEnd();

		getText().replace(
				Math.min(start, end),
				Math.max(start, end),
				"\t",
				0,
				1);
	}

	public void addUniform(String statement) {
		if (statement == null) {
			return;
		}

		Editable e = getText();
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

	private void init(Context context) {
		setHorizontallyScrolling(true);

		setFilters(new InputFilter[]{(source, start, end, dest, dstart, dend) -> {
			if (modified &&
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
			}

			@Override
			public void beforeTextChanged(
					CharSequence s,
					int start,
					int count,
					int after) {
			}

			@Override
			public void afterTextChanged(Editable e) {
				cancelUpdate();
				convertTabs(e, start, count);

				String converted = convertShaderToySource(e.toString());
				if (converted != null) {
					setTextHighlighted(converted);
				}

				if (!modified) {
					return;
				}

				dirty = true;
				updateHandler.postDelayed(updateRunnable, updateDelay);
			}
		});

		setSyntaxColors(context);
		setUpdateDelay(ShaderEditorApp.preferences.getUpdateDelay());
		setTabWidth(ShaderEditorApp.preferences.getTabWidth());

		setOnKeyListener((v, keyCode, event) -> {
			if (ShaderEditorApp.preferences.useTabForIndent() && event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_TAB) {
				// Insert a tab character instead of doing focus
				// navigation.
				insertTab();
				return true;
			}
			return false;
		});
	}

	private void setSyntaxColors(@NonNull Context context) {
		for (int i = 0, length = colors.length; i < length; ++i)
			colors[i] = ContextCompat.getColor(context, colors[i]);
		colorError = ContextCompat.getColor(context, R.color.syntax_error);
		highlighter = Highlighter.create(highlightNames, colors);
	}

	private void cancelUpdate() {
		updateHandler.removeCallbacks(updateRunnable);
	}

	private void highlightWithoutChange(@NonNull Spannable e) {
		modified = false;
		highlight(e);
		modified = true;
	}

	private Spannable highlight(@NonNull Spannable e) {
		if (errorLine > 0)
			post(() -> {
				if (e.length() == 0) return;
				Layout layout = getLayout();
				int start = layout.getLineStart(errorLine);
				int end = layout.getLineEnd(errorLine);
				e.setSpan(
						new BackgroundColorSpan(colorError),
						start,
						end,
						Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
			});
		int length = e.length();
		// Don't use e.clearSpans() because it will
		// remove too much.
		clearSpans(e, length);
		if (length == 0) {
			return e;
		}
		if (highlighter != null)
			highlighter.highlight(e.toString(), e);
		return e;
	}

	private static void clearSpans(Spannable e, int length) {
		// Remove foreground color spans.
		CharacterStyle[] spans = e.getSpans(
				0,
				length,
				ForegroundColorSpan.class);
		for (int i = spans.length; i-- > 0; ) {
			e.removeSpan(spans[i]);
		}

		// Remove background color spans.
		spans = e.getSpans(
				0,
				length,
				BackgroundColorSpan.class);

		for (int i = spans.length; i-- > 0; ) {
			e.removeSpan(spans[i]);
		}
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

	private void convertTabs(Editable e, int start, int count) {
		if (tabWidth < 1) {
			return;
		}

		String s = e.toString();

		for (int stop = start + count;
			 (start = s.indexOf("\t", start)) > -1 && start < stop;
			 ++start) {
			e.setSpan(
					new TabWidthSpan(tabWidth),
					start,
					start + 1,
					Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
	}

	private static String convertShaderToySource(String src) {
		if (!PATTERN_SHADER_TOY.matcher(src).find() ||
				PATTERN_MAIN.matcher(src).find()) {
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

	private static class TabWidthSpan extends ReplacementSpan {
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
	}
}
