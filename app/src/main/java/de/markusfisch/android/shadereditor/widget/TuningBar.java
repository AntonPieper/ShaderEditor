package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.math.BigDecimal;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.tuning.LiteralText;
import de.markusfisch.android.shadereditor.tuning.TunedPlace;
import de.markusfisch.android.shadereditor.tuning.TuningStep;
import de.markusfisch.android.shadereditor.view.SoftKeyboard;

/**
 * The bar that tunes one number of the code while the picture shows:
 * the tuned line, the value between − and +, a ruler without ends, and
 * Revert, the step and Done. What is tuned, its step and where it
 * started are one value, {@link Tuned}; the bar shows it and tells its
 * {@link Session} every change. Main thread.
 */
public class TuningBar extends LinearLayout {
	/** Where the values of the bar go. */
	public interface Session {
		/** Show what tuned has now. */
		void set(@NonNull Tuned tuned);

		/**
		 * The bar closes: keep the value (Done, Back), or the number
		 * stays as it was (Revert).
		 */
		void end(boolean keep);
	}

	/**
	 * What is tuned: the place, the value it started at and has now,
	 * the step, and the literal whose style the value is written in,
	 * which is what was typed once something was.
	 */
	public record Tuned(@NonNull TunedPlace place, @NonNull BigDecimal start,
			@NonNull BigDecimal value, @NonNull BigDecimal step,
			@NonNull BigDecimal firstStep, @NonNull String style) {
		@NonNull
		Tuned with(@NonNull BigDecimal value) {
			return new Tuned(place, start, value, step,
					firstStep, style);
		}

		@NonNull
		Tuned withStep(@NonNull BigDecimal step) {
			return new Tuned(place, start, value, step,
					firstStep, style);
		}

		@NonNull
		Tuned typed(@NonNull String literal) {
			BigDecimal typedValue = LiteralText.value(literal.startsWith("-")
					? literal.substring(1)
					: literal);
			if (typedValue == null) {
				return this;
			}
			return new Tuned(place, start,
					literal.startsWith("-") ? typedValue.negate() : typedValue,
					step, firstStep, literal.startsWith("-")
					? literal.substring(1)
					: literal);
		}

		/**
		 * What is written from the place's signStart to its end: the
		 * text as it was while the value is the one it started at.
		 */
		@NonNull
		public String text() {
			return changed() || !style.equals(place.literal)
					? place.text(style, value, step)
					: place.text(place.literal, value, BigDecimal.ONE);
		}

		/** What the literal alone is built as, the minus in front kept. */
		@NonNull
		public String literalText() {
			return place.literalText(style, value, step);
		}

		/** The number the literal alone stands for. */
		public double literalValue() {
			return place.literalValue(value);
		}

		/** Whether the value is another than the one it started at. */
		public boolean changed() {
			return value.compareTo(start) != 0;
		}

		boolean integer() {
			return place.type != de.markusfisch.android.shadereditor.opengl
					.Tuning.Type.FLOAT;
		}
	}

	@Nullable
	private Tuned tuned;
	@Nullable
	private Session session;
	private TextView lineView;
	private EditText valueView;
	private MaterialButton decrease;
	private MaterialButton increase;
	private MaterialButton stepView;
	private TuningRuler ruler;
	// The value when a drag on the ruler began.
	@NonNull
	private BigDecimal dragFrom = BigDecimal.ZERO;
	private int bottomInset;

	public TuningBar(Context context) {
		super(context);
	}

	public TuningBar(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();
		lineView = findViewById(R.id.tuning_line);
		valueView = findViewById(R.id.tuning_value);
		decrease = findViewById(R.id.tuning_decrease);
		increase = findViewById(R.id.tuning_increase);
		stepView = findViewById(R.id.tuning_step);
		ruler = findViewById(R.id.tuning_ruler);
		lineView.setTypeface(ShaderEditorApp.preferences.getFont());
		valueView.setTypeface(ShaderEditorApp.preferences.getFont());
		decrease.setOnClickListener(v -> move(-1));
		increase.setOnClickListener(v -> move(1));
		stepView.setOnClickListener(v -> chooseStep());
		findViewById(R.id.tuning_revert).setOnClickListener(v -> revert());
		findViewById(R.id.tuning_done).setOnClickListener(v -> done());
		valueView.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_DONE ||
					(event != null &&
							event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
				takeTyped();
				return true;
			}
			return false;
		});
		ruler.setListener(new TuningRuler.Listener() {
			@Override
			public void onDragStart() {
				if (tuned != null) {
					dragFrom = tuned.value();
				}
			}

			@Override
			public void onSteps(long count) {
				if (tuned != null) {
					change(TuningStep.move(dragFrom, tuned.step(), count,
							tuned.place().type), false);
				}
			}
		});
		ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
			// Above the keyboard while a value is typed.
			Insets bars = insets.getInsets(
					WindowInsetsCompat.Type.navigationBars() |
							WindowInsetsCompat.Type.ime());
			bottomInset = bars.bottom;
			setPadding(bars.left, 0, bars.right, bottomInset);
			return insets;
		});
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		// The content at most 600 dp wide, centred, on wide screens.
		View content = findViewById(R.id.tuning_content);
		int max = Math.round(600 * getResources().getDisplayMetrics().density);
		LayoutParams params = (LayoutParams) content.getLayoutParams();
		int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() -
				getPaddingRight();
		int wanted = width > max ? max : LayoutParams.MATCH_PARENT;
		if (params.width != wanted) {
			params.width = wanted;
			content.setLayoutParams(params);
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		post(this::showLine);
	}

	/** Whether the bar is open. */
	public boolean isOpen() {
		return tuned != null;
	}

	/** The state of the bar, or null when it is closed. */
	@Nullable
	public Tuned tuned() {
		return tuned;
	}

	/**
	 * Open the bar on place with step, or the first step for it when
	 * null; session hears every change.
	 */
	public void open(@NonNull TunedPlace place, @Nullable BigDecimal step,
			@NonNull Session session) {
		BigDecimal first = TuningStep.first(place.literal,
				place.value.abs(), place.degrees);
		this.session = session;
		tuned = new Tuned(place, place.value, place.value,
				step != null ? step : first, first, place.literal);
		setVisibility(VISIBLE);
		show();
	}

	/** Close the bar without telling the session: it ended. */
	public void close() {
		tuned = null;
		session = null;
		SoftKeyboard.hide(getContext(), valueView);
		valueView.clearFocus();
		setVisibility(GONE);
	}

	/** Done: the value is written. */
	public void done() {
		end(true);
	}

	/** Back keeps the value, as Done does. */
	public void back() {
		end(true);
	}

	/** Revert: the start value comes back, nothing is written. */
	public void revert() {
		Tuned now = tuned;
		if (now == null) {
			return;
		}
		ruler.slideTo(now.start(), 250);
		change(now.start(), true);
		end(false);
	}

	private void end(boolean keep) {
		Tuned now = tuned;
		Session ending = session;
		if (now == null || ending == null) {
			return;
		}
		if (valueView.hasFocus()) {
			takeTyped();
			now = tuned;
		}
		close();
		ending.end(keep && now.changed());
	}

	private void move(long steps) {
		Tuned now = tuned;
		if (now != null) {
			change(TuningStep.move(now.value(), now.step(), steps,
					now.place().type), true);
		}
	}

	private void change(@NonNull BigDecimal value, boolean moveRuler) {
		Tuned now = tuned;
		if (now == null || value.compareTo(now.value()) == 0) {
			return;
		}
		tuned = now.with(value);
		if (session != null) {
			session.set(tuned);
		}
		if (moveRuler) {
			ruler.show(value, tuned.start(), tuned.step());
		}
		showValue();
		showLine();
	}

	private void takeTyped() {
		Tuned now = tuned;
		if (now == null) {
			return;
		}
		String literal = LiteralText.typed(now.place().literal,
				valueView.getText().toString());
		if (literal == null) {
			valueView.setError(getContext().getString(
					R.string.tuning_not_a_number));
			return;
		}
		Tuned typed = now.typed(literal);
		SoftKeyboard.hide(getContext(), valueView);
		valueView.clearFocus();
		if (typed.value().compareTo(now.value()) == 0) {
			tuned = typed;
			showValue();
			return;
		}
		tuned = new Tuned(now.place(), now.start(), now.value(), now.step(), now.firstStep(),
				typed.style());
		change(typed.value(), true);
	}

	private void chooseStep() {
		Tuned now = tuned;
		if (now == null) {
			return;
		}
		List<BigDecimal> steps = TuningStep.menu(now.firstStep(),
				now.integer(), now.place().degrees);
		PopupMenu menu = new PopupMenu(getContext(), stepView);
		Menu items = menu.getMenu();
		for (int i = 0; i < steps.size(); ++i) {
			MenuItem item = items.add(1, i, i,
					TuningStep.label(steps.get(i)));
			item.setChecked(steps.get(i).compareTo(now.step()) == 0);
		}
		items.setGroupCheckable(1, true, true);
		items.add(2, steps.size(), steps.size(),
				R.string.tuning_step_other);
		menu.setOnMenuItemClickListener(item -> {
			if (item.getItemId() < steps.size()) {
				setStep(steps.get(item.getItemId()));
			} else {
				typeStep();
			}
			return true;
		});
		menu.show();
	}

	private void typeStep() {
		Tuned now = tuned;
		if (now == null) {
			return;
		}
		Context context = getContext();
		View view = inflate(context, R.layout.dialog_tuning_step, null);
		EditText field = view.findViewById(R.id.step);
		field.setText(TuningStep.label(now.step()));
		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.tuning_step_title)
				.setView(view)
				.setNegativeButton(android.R.string.cancel, null)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					BigDecimal step = TuningStep.parse(
							field.getText().toString(), now.place().type);
					if (step != null) {
						setStep(step);
					}
				})
				.show();
	}

	private void setStep(@NonNull BigDecimal step) {
		Tuned now = tuned;
		if (now == null) {
			return;
		}
		tuned = now.withStep(step);
		if (session != null) {
			session.set(tuned);
		}
		show();
	}

	private void show() {
		Tuned now = tuned;
		if (now == null) {
			return;
		}
		ruler.show(now.value(), now.start(), now.step());
		String step = TuningStep.label(now.step());
		stepView.setText(getContext().getString(R.string.tuning_step, step));
		decrease.setContentDescription(getContext().getString(
				R.string.tuning_decrease, step));
		increase.setContentDescription(getContext().getString(
				R.string.tuning_increase, step));
		showValue();
		showLine();
	}

	private void showValue() {
		Tuned now = tuned;
		if (now != null && !valueView.hasFocus()) {
			valueView.setText(now.text().replace("(", "").replace(")", ""));
		}
	}

	// The tuned line with the new digits, cut on both sides around them
	// so that they stay in the middle.
	private void showLine() {
		Tuned now = tuned;
		int width = lineView.getWidth() - lineView.getPaddingLeft() -
				lineView.getPaddingRight();
		if (now == null || width <= 0) {
			return;
		}
		TunedPlace place = now.place();
		int from = place.signStart - place.lineStart();
		int to = place.end - place.lineStart();
		String text = now.text();
		String line = place.line();
		String before = line.substring(0, Math.max(0, from)).replace('\t', ' ');
		String after = line.substring(Math.min(line.length(), to))
				.replace('\t', ' ');
		Paint paint = lineView.getPaint();
		String ellipsis = "…";
		int left = before.length();
		int right = 0;
		// Grow the window around the digits one character at a time on
		// each side while it fits.
		boolean grew = true;
		while (grew) {
			grew = false;
			if (left > 0 && fits(paint, ellipsis, before, left - 1, text,
					after, right, width)) {
				--left;
				grew = true;
			}
			if (right < after.length() && fits(paint, ellipsis, before, left,
					text, after, right + 1, width)) {
				++right;
				grew = true;
			}
		}
		left = Math.min(left, before.length());
		SpannableStringBuilder shown = new SpannableStringBuilder();
		if (left > 0) {
			shown.append(ellipsis);
		}
		shown.append(before.substring(left));
		int digits = shown.length();
		shown.append(text);
		int digitsEnd = shown.length();
		shown.append(after, 0, right);
		if (right < after.length()) {
			shown.append(ellipsis);
		}
		SpannableString coloured = DocsSheet.colour(getContext(),
				shown.toString());
		coloured.setSpan(new BackgroundColorSpan(ContextCompat.getColor(
						getContext(), R.color.code_selection)), digits,
				digitsEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		lineView.setText(coloured);
	}

	private static boolean fits(@NonNull Paint paint, @NonNull String ellipsis,
			@NonNull String before, int left, @NonNull String text,
			@NonNull String after, int right, int width) {
		String shown = (left > 0 ? ellipsis : "") + before.substring(left) +
				text + after.substring(0, right) +
				(right < after.length() ? ellipsis : "");
		return paint.measureText(shown) <= width;
	}
}
