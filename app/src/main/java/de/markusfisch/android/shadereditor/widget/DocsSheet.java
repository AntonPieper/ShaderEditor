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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * is in the editor's font and colours, laid out to fit its box (see
 * wrap); no line is wider than the box. A part that is null is not
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
	// The indents of a part, widest first: four characters where they
	// fit, else two, else one.
	private static final String[] STEPS = {"    ", "  ", " "};
	private static final String OPERATORS = "+-*/%=<>&|^?:";
	// Binary operators, lowest precedence first.
	private static final String[] PRECEDENCE = {"= += -= *= /=", "? :",
			"|| ^^", "&&", "== !=", "< > <= >=", "+ -", "* / %"};
	// The head of a #define: the name and its parameters, if any.
	private static final Pattern DEFINE = Pattern.compile(
			"\\s*#\\s*define\\s+\\w+(\\([^)]*\\))?");

	private final BottomSheetDialog dialog;
	private final View view;

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
		view = LayoutInflater.from(context).inflate(R.layout.sheet_docs,
				null);
		TextView word = view.findViewById(R.id.docs_word);
		word.setTypeface(ShaderEditorApp.preferences.getFont());
		word.setText(content.word());
		ViewCompat.setAccessibilityHeading(word, true);
		((TextView) view.findViewById(R.id.docs_kind)).setText(content.kind());
		setText(view.findViewById(R.id.docs_text), content.text());
		TextView code = view.findViewById(R.id.docs_code);
		setCode(code, content.code());
		code.setContentDescription(content.spoken() != null
				? content.spoken()
				: content.code());
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
		view.findViewById(R.id.docs_links).setVisibility(
				view.findViewById(R.id.docs_link).getVisibility());
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

	/** Offer Uses beside the action, doing uses; the sheet closes first. */
	public void setUses(@NonNull Runnable uses) {
		View button = view.findViewById(R.id.docs_uses);
		button.setVisibility(View.VISIBLE);
		view.findViewById(R.id.docs_links).setVisibility(View.VISIBLE);
		button.setOnClickListener(v -> {
			dialog.dismiss();
			uses.run();
		});
	}

	/** Run when the sheet goes, however it goes. */
	public void setOnDismiss(@NonNull Runnable gone) {
		dialog.setOnDismissListener(d -> gone.run());
	}

	/**
	 * Return code with each line wider than width in paint laid out to
	 * fit, as a code formatter does: a statement breaks after the last
	 * comma or operator that fits; a #define has its head on a line, or
	 * broken after a comma, and its body under it; anything else is laid
	 * out by its parts, the arguments of a call, the statements of a
	 * block, the operands of its operators of the lowest precedence, a
	 * part a line each a step further in and laid out so again, so that a
	 * part that fits stays whole. The step is four characters, two or
	 * one: the one that gives the fewest lines that all fit, the wider on
	 * a tie. A line that fits at no step is cut after its last blank,
	 * comma or opening bracket that fits, the rest one column further in.
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
			sb.append(measure.width(line) <= width
					? line
					: fit(line, measure, width));
		}
		return sb.toString();
	}

	// line laid out at the indent step that gives the fewest lines that
	// all fit, the wider step where two give as many: fewer lines are
	// fewer calls broken that a narrower indent leaves whole. Where no
	// step fits, the lines still too wide are cut.
	@NonNull
	private static String fit(@NonNull String line,
			@NonNull MessageRow.Measure measure, float width) {
		String best = null;
		int lines = Integer.MAX_VALUE;
		for (String step : STEPS) {
			StringBuilder sb = new StringBuilder();
			Matcher define = DEFINE.matcher(line);
			if (define.lookingAt() && define.end() < line.length()) {
				// A head too wide breaks after a comma, the rest two steps
				// in, so its parameters are not taken for its body.
				breakStatement(sb, define.group().trim(), step + step,
						measure, width);
				sb.append('\n');
				lay(sb, step, step, "", line.substring(define.end()).trim(),
						"", measure, width);
			} else if (line.endsWith(";")) {
				breakStatement(sb, line, step, measure, width);
			} else {
				lay(sb, step, "", "", line, "", measure, width);
			}
			String laid = sb.toString();
			int count = laid.split("\n").length;
			if (count < lines && fits(laid, measure, width)) {
				best = laid;
				lines = count;
			}
			if (step.length() == 1 && best == null) {
				best = cut(laid, measure, width);
			}
		}
		return best;
	}

	private static boolean fits(@NonNull String text,
			@NonNull MessageRow.Measure measure, float width) {
		for (String line : text.split("\n")) {
			if (measure.width(line) > width) {
				return false;
			}
		}
		return true;
	}

	// Each line of text still wider than width broken after the last
	// blank, comma or opening bracket that fits, else after as much as
	// fits, the rest one column further in than the line: never wider
	// than the box, which would go on at its left edge.
	@NonNull
	private static String cut(@NonNull String text,
			@NonNull MessageRow.Measure measure, float width) {
		StringBuilder sb = new StringBuilder();
		for (String line : text.split("\n", -1)) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			int lead = lead(line);
			String hang = measure.width(line.substring(0, lead) + "  ") <=
					width ? line.substring(0, lead) + " " : "";
			while (measure.width(line) > width) {
				int at = 0;
				int fits = lead + 1;
				for (int i = lead + 1; i < line.length() &&
						measure.width(line.substring(0, i)) <= width; ++i) {
					fits = i;
					if (" ,(".indexOf(line.charAt(i - 1)) > -1) {
						at = i;
					}
				}
				at = at > lead ? at : fits;
				sb.append(stripEnd(line.substring(0, at))).append('\n');
				line = hang + line.substring(at).trim();
				lead = hang.length();
			}
			sb.append(line);
		}
		return sb.toString();
	}

	private static int lead(@NonNull String line) {
		int lead = 0;
		while (lead < line.length() && line.charAt(lead) == ' ') {
			++lead;
		}
		return lead;
	}

	// Appends head, text and tail at indent: on one line where that fits,
	// else by the parts of text, each a step further in.
	private static void lay(@NonNull StringBuilder sb, @NonNull String step,
			@NonNull String indent, @NonNull String head,
			@NonNull String text, @NonNull String tail,
			@NonNull MessageRow.Measure measure, float width) {
		String line = indent + head + text + tail;
		int open = text.indexOf(text.startsWith("{") ? '{' : '(');
		List<String> parts = measure.width(line) > width
				? parts(text, open)
				: null;
		if (parts != null && open > 0) {
			// A call: its arguments.
			sb.append(indent).append(head).append(text, 0, open + 1);
			for (int i = 0, n = parts.size(); i < n; ++i) {
				sb.append('\n');
				lay(sb, step, indent + step, "", parts.get(i),
						i < n - 1 ? "," : ")" + tail, measure, width);
			}
		} else if (parts != null && text.startsWith("{")) {
			sb.append(indent).append(head).append('{');
			for (String statement : parts) {
				sb.append('\n').append(indent).append(step)
						.append(statement).append(';');
			}
			sb.append('\n').append(indent).append('}').append(tail);
		} else if (parts != null) {
			lay(sb, step, indent, head + "(",
					text.substring(1, text.length() - 1), ")" + tail, measure,
					width);
		} else if (measure.width(line) > width &&
				(parts = operands(text)).size() > 2) {
			lay(sb, step, indent, head, parts.get(0), "", measure, width);
			for (int i = 1; i < parts.size(); i += 2) {
				sb.append('\n');
				lay(sb, step, indent + step, parts.get(i) + " ",
						parts.get(i + 1), i + 2 < parts.size() ? "" : tail,
						measure, width);
			}
		} else if (measure.width(line) > width && head.isEmpty()) {
			breakStatement(sb, line, step, measure, width);
		} else {
			sb.append(line);
		}
	}

	// The parts of text when the bracket at open closes at its end: the
	// arguments of a call or a group, the statements of a block; null for
	// anything else.
	@Nullable
	private static List<String> parts(@NonNull String text, int open) {
		boolean block = text.startsWith("{") && text.endsWith("}");
		if (open < 0 || !block && !text.endsWith(")")) {
			return null;
		}
		List<String> parts = new ArrayList<>();
		int depth = 0;
		int start = open + 1;
		for (int i = open; i < text.length(); ++i) {
			char c = text.charAt(i);
			if ("([{".indexOf(c) > -1) {
				++depth;
			} else if (")]}".indexOf(c) > -1 && --depth == 0 &&
					i < text.length() - 1) {
				return null;
			} else if (depth == 1 && c == (block ? ';' : ',')) {
				parts.add(text.substring(start, i).trim());
				start = i + 1;
			}
		}
		String last = text.substring(start, text.length() - 1).trim();
		if (!block || !last.isEmpty()) {
			parts.add(last);
		}
		return depth != 0 || parts.isEmpty() || parts.contains("")
				? null
				: parts;
	}

	// Text split around its operators of the lowest precedence outside
	// brackets: operand, operator, operand and so on.
	@NonNull
	private static List<String> operands(@NonNull String text) {
		String[] tokens = text.split(" ", -1);
		int lowest = PRECEDENCE.length;
		int depth = 0;
		for (String token : tokens) {
			lowest = Math.min(lowest, depth == 0 ? level(token) : lowest);
			depth += count(token, "([{") - count(token, ")]}");
		}
		List<String> parts = new ArrayList<>();
		StringBuilder operand = new StringBuilder();
		depth = 0;
		for (String token : tokens) {
			if (depth == 0 && level(token) == lowest && operand.length() > 0) {
				parts.add(operand.toString().trim());
				parts.add(token);
				operand.setLength(0);
			} else {
				operand.append(token).append(' ');
			}
			depth += count(token, "([{") - count(token, ")]}");
		}
		parts.add(operand.toString().trim());
		return parts;
	}

	private static int level(@NonNull String token) {
		for (int i = 0; i < PRECEDENCE.length; ++i) {
			if ((" " + PRECEDENCE[i] + " ").contains(" " + token + " ")) {
				return i;
			}
		}
		return PRECEDENCE.length;
	}

	private static int count(@NonNull String token, @NonNull String chars) {
		int n = 0;
		for (int i = 0; i < token.length(); ++i) {
			n += chars.indexOf(token.charAt(i)) > -1 ? 1 : 0;
		}
		return n;
	}

	// Appends line, wider than width, to sb broken after the last comma
	// or operator that fits, the rest indented by continuation more than
	// the line, as often as it takes; never in the middle of a word.
	private static void breakStatement(@NonNull StringBuilder sb,
			@NonNull String line, @NonNull String continuation,
			@NonNull MessageRow.Measure measure, float width) {
		int lead = lead(line);
		String indent = line.substring(0, lead) + continuation;
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
		// What is said is the code, not how it is laid out.
		view.setContentDescription(code);
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
