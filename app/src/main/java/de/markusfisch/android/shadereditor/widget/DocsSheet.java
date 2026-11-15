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
 * an example. Code is in the editor's font and colours; a form wider
 * than its box takes a line per parameter. A part that is null is not
 * shown. The sheet opens fitted to its content, at most 90 % of the
 * screen high, and scrolls inside.
 */
public final class DocsSheet {
	/**
	 * What the sheet says; only word, kind and text are needed. spoken
	 * is what TalkBack says for code instead of its characters.
	 */
	public record Content(@NonNull String word, @NonNull String kind,
			@NonNull String text, @Nullable String code,
			@Nullable String spoken, @Nullable String detail,
			@Nullable String note, @Nullable String example) {
	}

	private static final float MAX_HEIGHT = .9f;
	private static final String INDENT = "  ";

	private final BottomSheetDialog dialog;

	// The sheet gives its content a parent only once it is set.
	@SuppressLint("InflateParams")
	public DocsSheet(@NonNull Context context, @NonNull Content content) {
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
	 * a parameter per line.
	 */
	@NonNull
	static String wrap(@NonNull String code, @NonNull TextPaint paint,
			float width) {
		StringBuilder sb = new StringBuilder();
		for (String line : code.split("\n", -1)) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			int open = line.indexOf('(');
			if (open < 0 || !line.endsWith(")") || line.endsWith("()") ||
					paint.measureText(line) <= width) {
				sb.append(line);
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
	private static SpannableString colour(@NonNull Context context,
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
