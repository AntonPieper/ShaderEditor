package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.highlighter.Highlight;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * What a word of the code is, in a sheet: the word, what kind of word
 * it is, what it means, its forms or declaration, the notes on them and
 * an example, and one action: a filled button for what changes the
 * text, a text button for what leads to where a name is declared. Code
 * is in the editor's font and colours; a form wider than its box takes
 * a line per parameter, indented by four characters, and any other line
 * breaks after a comma or an operator, the rest indented by four
 * characters too. A part that is null is not
 * shown. The sheet opens fitted to its content, at most 90 % of the
 * screen high, and scrolls inside.
 */
public final class DocsSheet {
	/**
	 * What the sheet says; only word and kind are needed. spoken
	 * is what TalkBack says for code instead of its characters.
	 */
	public record Content(@NonNull String word, @NonNull String kind,
			@Nullable String text, @Nullable String code,
			@Nullable String spoken, @Nullable String detail,
			@Nullable String note, @Nullable String example) {
	}

	private static final float MAX_HEIGHT = .9f;
	// The indent of a form's parameters, and of the rest of a statement
	// that is too wide: the same four characters.
	private static final String INDENT = "    ";
	private static final String CONTINUATION = INDENT;
	private static final String OPERATORS = "+-*/%=<>&|^?:";

	private final BottomSheetDialog dialog;

	/**
	 * @param action   the label of the button, or null for none
	 * @param onAction what it does; the sheet closes first
	 */
	public DocsSheet(@NonNull Context context, @NonNull Content content,
			@Nullable String action, @Nullable Runnable onAction) {
		this(context, content, action, onAction, false);
	}

	/**
	 * @param action   the label of the button, or null for none
	 * @param onAction what it does; the sheet closes first
	 * @param leads    whether the action leads somewhere, a text button,
	 *                 or changes the text, a filled one
	 */
	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public DocsSheet(@NonNull Context context, @NonNull Content content,
			@Nullable String action, @Nullable Runnable onAction,
			boolean leads) {
		View view = LayoutInflater.from(context).inflate(R.layout.sheet_docs,
				null);
		TextView word = view.findViewById(R.id.docs_word);
		word.setTypeface(ShaderEditorApp.preferences.getFont());
		word.setText(content.word());
		ViewCompat.setAccessibilityHeading(word, true);
		((TextView) view.findViewById(R.id.docs_kind)).setText(content.kind());
		setText(view.findViewById(R.id.docs_text), content.text());
		TextView code = view.findViewById(R.id.docs_code);
		setCode(code, content.code());
		code.setContentDescription(content.spoken());
		setText(view.findViewById(R.id.docs_detail), content.detail());
		setText(view.findViewById(R.id.docs_note), content.note());
		setCode(view.findViewById(R.id.docs_example), content.example());
		view.findViewById(R.id.docs_example_heading).setVisibility(
				content.example() != null ? View.VISIBLE : View.GONE);
		View inside = view.findViewById(R.id.docs_content);
		int bottom = inside.getPaddingBottom();
		ViewCompat.setOnApplyWindowInsetsListener(inside, (v, insets) -> {
			Insets bars = insets.getInsets(
					WindowInsetsCompat.Type.navigationBars());
			v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
					v.getPaddingRight(), bottom + bars.bottom);
			return insets;
		});
		dialog = new BottomSheetDialog(context);
		TextView button = view.findViewById(leads
				? R.id.docs_link
				: R.id.docs_action);
		view.findViewById(leads ? R.id.docs_action : R.id.docs_link)
				.setVisibility(View.GONE);
		if (action != null && onAction != null) {
			button.setText(action);
			button.setOnClickListener(v -> {
				dialog.dismiss();
				onAction.run();
			});
		} else {
			button.setVisibility(View.GONE);
		}
		dialog.setTitle(content.word());
		dialog.setContentView(view);
		BottomSheetBehavior<?> behavior = dialog.getBehavior();
		behavior.setMaxHeight(Math.round(MAX_HEIGHT *
				context.getResources().getDisplayMetrics().heightPixels));
		behavior.setSkipCollapsed(true);
		behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
	}

	public void show() {
		dialog.show();
	}

	/** Run when the sheet goes, however it goes. */
	public void setOnDismiss(@NonNull Runnable gone) {
		dialog.setOnDismissListener(d -> gone.run());
	}

	/**
	 * Return code with each line that is a call or declaration wider
	 * than width in paint broken after its parenthesis and its commas,
	 * a parameter per line indented by four characters, and any other
	 * line wider than width broken as the code says.
	 */
	@NonNull
	static String wrap(@NonNull String code, @NonNull TextPaint paint,
			float width) {
		return wrap(code, paint::measureText, width);
	}

	/** See wrap(String, TextPaint, float). */
	@NonNull
	static String wrap(@NonNull String code,
			@NonNull MessageRow.Measure measure, float width) {
		StringBuilder sb = new StringBuilder();
		for (String line : code.split("\n", -1)) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			if (measure.width(line) <= width) {
				sb.append(line);
				continue;
			}
			int open = line.indexOf('(');
			if (open < 0 || !line.endsWith(")") || line.endsWith("()")) {
				breakStatement(sb, line, measure, width);
				continue;
			}
			sb.append(line, 0, open + 1);
			for (String parameter : line.substring(open + 1,
					line.length() - 1).split(",")) {
				sb.append('\n').append(INDENT).append(parameter.trim())
						.append(',');
			}
			sb.setCharAt(sb.length() - 1, ')');
		}
		return sb.toString();
	}

	// Appends line, wider than width, to sb broken after the last comma
	// or operator that fits, the rest indented by four characters more
	// than the line, as often as it takes; never in the middle of a
	// word. Where nothing fits, the box breaks it.
	private static void breakStatement(@NonNull StringBuilder sb,
			@NonNull String line, @NonNull MessageRow.Measure measure,
			float width) {
		int lead = 0;
		while (lead < line.length() && line.charAt(lead) == ' ') {
			++lead;
		}
		String indent = line.substring(0, lead) + CONTINUATION;
		String rest = line;
		String prefix = "";
		while (measure.width(prefix + rest) > width) {
			int at = -1;
			for (int i = prefix.isEmpty() ? lead : 0, n = rest.length();
					i < n - 1; ++i) {
				if (breaksAfter(rest, i) && measure.width(prefix +
						rest.substring(0, i + 1)) <= width) {
					at = i + 1;
				}
			}
			if (at < 0) {
				break;
			}
			sb.append(prefix).append(stripEnd(rest.substring(0, at)))
					.append('\n');
			rest = rest.substring(at).trim();
			prefix = indent;
		}
		sb.append(prefix).append(rest);
	}

	// Whether a line may break after the character at i of text: a
	// comma, or an operator with a blank on both sides.
	private static boolean breaksAfter(@NonNull String text, int i) {
		char c = text.charAt(i);
		if (c == ',') {
			return true;
		}
		return OPERATORS.indexOf(c) > -1 && i > 0 && i + 1 < text.length() &&
				text.charAt(i - 1) == ' ' && text.charAt(i + 1) == ' ';
	}

	@NonNull
	private static String stripEnd(@NonNull String text) {
		int end = text.length();
		while (end > 0 && text.charAt(end - 1) == ' ') {
			--end;
		}
		return text.substring(0, end);
	}

	private static void setText(@NonNull TextView view,
			@Nullable String text) {
		view.setText(text);
		view.setVisibility(text != null ? View.VISIBLE : View.GONE);
	}

	private static void setCode(@NonNull TextView view,
			@Nullable String code) {
		if (code == null) {
			view.setVisibility(View.GONE);
			return;
		}
		view.setTypeface(ShaderEditorApp.preferences.getFont());
		view.setText(colour(view.getContext(), code));
		// Wrapped where the box is too narrow, once it is laid out.
		view.addOnLayoutChangeListener((v, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (right - left == oldRight - oldLeft) {
				return;
			}
			String wrapped = wrap(code, view.getPaint(), right - left -
					view.getTotalPaddingLeft() - view.getTotalPaddingRight());
			if (!wrapped.equals(view.getText().toString())) {
				view.post(() -> view.setText(colour(view.getContext(),
						wrapped)));
			}
		});
	}

	// Colours code as the editor does, but for names it takes for types,
	// which here are the letters of the forms, T, B and the like.
	@NonNull
	static SpannableString colour(@NonNull Context context,
			@NonNull String code) {
		SpannableString spans = new SpannableString(code);
		LineTokens tokens = new LineTokens();
		int state = Syntax.TEXT_START;
		int start = 0;
		while (start <= code.length()) {
			int end = code.indexOf('\n', start);
			boolean breakFollows = end > -1;
			if (!breakFollows) {
				end = code.length();
			}
			state = GlslSyntax.INSTANCE.lexLine(code.substring(start, end),
					breakFollows, state, tokens);
			for (int i = 0, n = tokens.size(); i < n; ++i) {
				Style style = tokens.style(i);
				if (style != Style.IDENTIFIER && style != Style.TYPE) {
					spans.setSpan(new ForegroundColorSpan(ContextCompat
									.getColor(context, Highlight.colorOf(style))),
							start + tokens.start(i), start + tokens.end(i),
							Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				}
			}
			start = end + 1;
		}
		return spans;
	}
}
