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
import android.view.ViewGroup;
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
import de.markusfisch.android.shadereditor.view.SystemBarMetrics;

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

		/**
		 * Tune part index of the vector instead, keeping the value of
		 * the one tuned now; the bar is told with {@link #retarget}.
		 */
		default void selectPart(int index) {
		}
	}

	private static final String[] XYZW = {"x", "y", "z", "w"};
	private static final String[] RGBA = {"r", "g", "b", "a"};

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
	// The view the bar stays below, so the bars above it can be read.
	@Nullable
	private View ceiling;
	// Whether a vec3 or vec4 is shown as a colour, and the views of its
	// parts in either mode.
	private boolean colourMode;
	private com.google.android.material.button.MaterialButtonToggleGroup partsView;
	private MaterialButton colourView;
	private LinearLayout coloursView;
	private final java.util.List<com.google.android.material.slider.Slider> sliders =
			new java.util.ArrayList<>();
	private final java.util.List<TextView> sliderValues = new java.util.ArrayList<>();
	// Whether the bar sets the parts' views itself, not the user.
	private boolean showing;

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
		partsView = findViewById(R.id.tuning_parts);
		colourView = findViewById(R.id.tuning_colour);
		coloursView = findViewById(R.id.tuning_colours);
		colourView.setOnClickListener(v -> setColourMode(!colourMode));
		partsView.addOnButtonCheckedListener((group, id, checked) -> {
			if (checked && !showing) {
				selectPart(id);
			}
		});
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
			public void onStep(long count) {
				move(count);
			}

			@NonNull
			@Override
			public CharSequence describe() {
				Tuned now = tuned;
				return now == null
						? ""
						: getContext().getString(R.string.a11y_tuning_value,
						now.text().replace("(", "").replace(")", ""),
						LiteralText.write(now.place().literal, now.start(),
								now.step()));
			}

			@Override
			public void onDragStart() {
				// A drag ends typing: the field shows the ruler's value.
				dropTyping();
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
			bottomInset = SystemBarMetrics.bottomInset(v, insets);
			setPadding(bars.left, 0, bars.right, bottomInset);
			return insets;
		});
	}

	/**
	 * Keep the bar below the top of view, a view inside the bar's
	 * parent: what is above it, as the toolbar, is never covered. Where
	 * the room is shorter than the bar, its content scrolls.
	 */
	public void setCeiling(@NonNull View view) {
		ceiling = view;
		view.addOnLayoutChangeListener((v, left, top, right, bottom,
				oldLeft, oldTop, oldRight, oldBottom) -> {
			if (top != oldTop) {
				requestLayout();
			}
		});
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		// No higher than the room under the bars above, as in landscape
		// with a keyboard while a value is typed.
		if (ceiling != null &&
				MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
			heightMeasureSpec = MeasureSpec.makeMeasureSpec(Math.max(0,
							MeasureSpec.getSize(heightMeasureSpec) -
									ceilingTop()),
					MeasureSpec.AT_MOST);
		}
		// The content at most 600 dp wide, centred, on wide screens; in
		// landscape the one row takes the width, the ruler what is left.
		View content = findViewById(R.id.tuning_content);
		boolean landscape = getResources().getConfiguration().orientation ==
				android.content.res.Configuration.ORIENTATION_LANDSCAPE;
		int max = landscape
				? Integer.MAX_VALUE
				: Math.round(600 * getResources().getDisplayMetrics().density);
		ViewGroup.LayoutParams params = content.getLayoutParams();
		int width = MeasureSpec.getSize(widthMeasureSpec) - getPaddingLeft() -
				getPaddingRight();
		int wanted = width > max ? max : LayoutParams.MATCH_PARENT;
		if (params.width != wanted) {
			params.width = wanted;
			content.setLayoutParams(params);
		}
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		// Done goes on a line of its own, at the end, when the session's
		// row is too narrow for all three, so that nothing is cut.
		LinearLayout session = findViewById(R.id.tuning_session);
		View done = findViewById(R.id.tuning_done);
		if (done.getParent() != session) {
			// The landscape row: Done is in it, not under it.
			return;
		}
		int needed = 0;
		for (int i = 0; i < session.getChildCount(); ++i) {
			View child = session.getChildAt(i);
			if (child.getVisibility() != GONE &&
					child.getId() != R.id.tuning_session_space) {
				LayoutParams lp = (LayoutParams) child.getLayoutParams();
				child.measure(MeasureSpec.makeMeasureSpec(0,
								MeasureSpec.UNSPECIFIED),
						MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
				needed += child.getMeasuredWidth() + lp.leftMargin +
						lp.rightMargin;
			}
		}
		boolean wrap = needed > session.getMeasuredWidth();
		int orientation = wrap ? VERTICAL : HORIZONTAL;
		if (session.getOrientation() != orientation) {
			session.setOrientation(orientation);
			session.setGravity(wrap
					? android.view.Gravity.START
					: android.view.Gravity.CENTER_VERTICAL);
			LayoutParams lp = (LayoutParams) done.getLayoutParams();
			lp.gravity = wrap ? android.view.Gravity.END : 0;
			done.setLayoutParams(lp);
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		}
	}

	// The top of the ceiling in the bar's parent.
	private int ceilingTop() {
		int top = 0;
		for (View view = ceiling; view != null && view != getParent();
				view = view.getParent() instanceof View parent
						? parent
						: null) {
			top += view.getTop();
		}
		return top;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		post(this::showLine);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		// Enter is Done and Escape is Revert, unless a value is typed.
		if (tuned != null && !valueView.hasFocus() &&
				event.getAction() == KeyEvent.ACTION_DOWN) {
			switch (event.getKeyCode()) {
				case KeyEvent.KEYCODE_ENTER:
				case KeyEvent.KEYCODE_NUMPAD_ENTER:
					done();
					return true;
				case KeyEvent.KEYCODE_ESCAPE:
					revert();
					return true;
				case KeyEvent.KEYCODE_DPAD_LEFT:
				case KeyEvent.KEYCODE_DPAD_RIGHT:
					if (!ruler.hasFocus()) {
						move((event.getKeyCode() ==
								KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1) *
								(event.isShiftPressed() ? 10L : 1L));
						return true;
					}
					break;
				default:
					break;
			}
		}
		return super.dispatchKeyEvent(event);
	}

	// Whether animations run: "Remove animations" makes them instant.
	private boolean animates() {
		return android.provider.Settings.Global.getFloat(
				getContext().getContentResolver(),
				android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
				1f) > 0f;
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
		colourMode = false;
		showParts();
		show();
		// Fades in and rises 16 dp, emphasized decelerate, 200 ms.
		setVisibility(VISIBLE);
		animate().cancel();
		if (animates()) {
			setAlpha(0f);
			setTranslationY(16 * getResources().getDisplayMetrics().density);
			animate().alpha(1f).translationY(0f).setDuration(200)
					.setInterpolator(new android.view.animation.PathInterpolator(
							0.05f, 0.7f, 0.1f, 1f))
					.withEndAction(null).start();
		} else {
			setAlpha(1f);
			setTranslationY(0f);
		}
		ruler.requestFocus();
		announceForAccessibility(getContext().getString(
				R.string.a11y_tuning_open, place.literalWithSign()));
	}

	/**
	 * Tune place, another part of the same vector, with step or its
	 * first step; the mode stays.
	 */
	public void retarget(@NonNull TunedPlace place, @Nullable BigDecimal step) {
		BigDecimal first = TuningStep.first(place.literal,
				place.value.abs(), place.degrees);
		tuned = new Tuned(place, place.value, place.value,
				step != null ? step : first, first, place.literal);
		showParts();
		show();
	}

	private void selectPart(int index) {
		Tuned now = tuned;
		TunedPlace.Vector vector = now != null ? now.place().vector() : null;
		if (vector != null && index != vector.index() && session != null) {
			session.selectPart(index);
		}
	}

	// Whether the vector of what is tuned can be a colour: a vec3 or
	// vec4 of floats.
	private boolean colourable() {
		Tuned now = tuned;
		TunedPlace.Vector vector = now != null ? now.place().vector() : null;
		return vector != null && !now.integer() &&
				("vec3".equals(vector.type()) || "vec4".equals(vector.type()));
	}

	private void setColourMode(boolean colour) {
		colourMode = colour && colourable();
		// Numbers and colour fade through, 200 ms.
		View fading = colourMode ? coloursView : findViewById(R.id.tuning_numbers);
		if (animates()) {
			fading.setAlpha(0f);
			fading.animate().alpha(1f).setDuration(200).start();
		}
		showNumbers(!colourMode);
		coloursView.setVisibility(colourMode ? VISIBLE : GONE);
		partsView.setVisibility(colourMode ? GONE : VISIBLE);
		showParts();
	}

	// Shows or hides what tunes a number: −, the value, + and the ruler.
	// In landscape Revert, the step and Done share their row and stay.
	private void showNumbers(boolean shown) {
		View numbers = findViewById(R.id.tuning_numbers);
		if (findViewById(R.id.tuning_done).getParent() != numbers) {
			numbers.setVisibility(shown ? VISIBLE : GONE);
			return;
		}
		int visibility = shown ? VISIBLE : GONE;
		for (int id : new int[]{R.id.tuning_decrease, R.id.tuning_value,
				R.id.tuning_increase, R.id.tuning_ruler}) {
			findViewById(id).setVisibility(visibility);
		}
		// The step's chip and Done keep the row's end.
		findViewById(R.id.tuning_step).setVisibility(visibility);
	}

	// The parts of the vector: a segment each, the one tuned checked;
	// in colour mode a slider each from 0 to 1.
	private void showParts() {
		Tuned now = tuned;
		TunedPlace.Vector vector = now != null ? now.place().vector() : null;
		View row = findViewById(R.id.tuning_parts_row);
		if (vector == null) {
			row.setVisibility(GONE);
			coloursView.setVisibility(GONE);
			showNumbers(true);
			colourMode = false;
			return;
		}
		row.setVisibility(VISIBLE);
		colourView.setVisibility(colourable() ? VISIBLE : GONE);
		colourView.setText(colourMode
				? R.string.tuning_as_numbers
				: R.string.tuning_colour);
		java.util.List<TunedPlace> parts = vector.parts();
		showing = true;
		try {
			if (partsView.getChildCount() != parts.size()) {
				partsView.removeAllViews();
				for (int i = 0; i < parts.size(); ++i) {
					MaterialButton part = new MaterialButton(getContext(), null,
							com.google.android.material.R.attr.materialButtonOutlinedStyle);
					part.setId(i);
					part.setCheckable(true);
					part.setTypeface(ShaderEditorApp.preferences.getFont());
					part.setMinHeight(Math.round(40 *
							getResources().getDisplayMetrics().density));
					part.setMinWidth(0);
					part.setPadding(Math.round(4 *
							getResources().getDisplayMetrics().density), 0,
							Math.round(4 *
									getResources().getDisplayMetrics().density),
							0);
					part.setMaxLines(1);
					partsView.addView(part, new LinearLayout.LayoutParams(0,
							LayoutParams.WRAP_CONTENT, 1));
				}
			}
			for (int i = 0; i < parts.size(); ++i) {
				MaterialButton part = (MaterialButton) partsView.getChildAt(i);
				part.setText(getContext().getString(R.string.tuning_part,
						XYZW[Math.min(i, 3)], partText(i)));
			}
			partsView.check(vector.index());
			showColours(parts.size());
		} finally {
			showing = false;
		}
		colourView.setIcon(swatch());
	}

	// What part i holds now, as written.
	@NonNull
	private String partText(int i) {
		Tuned now = tuned;
		TunedPlace.Vector vector = now.place().vector();
		return i == vector.index()
				? now.text().replace("(", "").replace(")", "")
				: vector.parts().get(i).literalWithSign();
	}

	private BigDecimal partValue(int i) {
		Tuned now = tuned;
		TunedPlace.Vector vector = now.place().vector();
		return i == vector.index() ? now.value() : vector.parts().get(i).value;
	}

	private void showColours(int count) {
		if (!colourMode) {
			return;
		}
		if (sliders.size() != count) {
			coloursView.removeAllViews();
			sliders.clear();
			sliderValues.clear();
			for (int i = 0; i < count; ++i) {
				View line = inflate(getContext(), R.layout.tuning_colour_part,
						null);
				((TextView) line.findViewById(R.id.letter)).setText(
						RGBA[Math.min(i, 3)]);
				com.google.android.material.slider.Slider slider =
						line.findViewById(R.id.slider);
				TextView value = line.findViewById(R.id.value);
				value.setTypeface(ShaderEditorApp.preferences.getFont());
				final int part = i;
				slider.addOnChangeListener((s, v, fromUser) -> {
					if (fromUser && !showing) {
						setColour(part, v);
					}
				});
				sliders.add(slider);
				sliderValues.add(value);
				coloursView.addView(line);
			}
		}
		for (int i = 0; i < count; ++i) {
			BigDecimal value = partValue(i);
			float clamped = Math.max(0f, Math.min(1f, value.floatValue()));
			sliders.get(i).setValue(clamped);
			TextView text = sliderValues.get(i);
			text.setText(partText(i));
			// Above 1 is kept, and shown in amber.
			text.setTextColor(value.compareTo(BigDecimal.ONE) > 0
					? ContextCompat.getColor(getContext(), R.color.se_warning)
					: com.google.android.material.color.MaterialColors.getColor(
					text, com.google.android.material.R.attr.colorOnSurface));
		}
	}

	private void setColour(int part, float value) {
		Tuned now = tuned;
		if (now == null || now.place().vector() == null) {
			return;
		}
		if (part != now.place().vector().index()) {
			selectPart(part);
			now = tuned;
			if (now == null || now.place().vector().index() != part) {
				return;
			}
		}
		int scale = Math.max(2, now.step().stripTrailingZeros().scale());
		change(new BigDecimal(value).setScale(scale,
				java.math.RoundingMode.HALF_EVEN), true);
	}

	// The colour the parts would be, as a 16 dp swatch.
	@Nullable
	private android.graphics.drawable.Drawable swatch() {
		if (!colourable()) {
			return null;
		}
		float dp = getResources().getDisplayMetrics().density;
		android.graphics.drawable.GradientDrawable swatch =
				new android.graphics.drawable.GradientDrawable();
		swatch.setCornerRadius(4 * dp);
		swatch.setSize(Math.round(16 * dp), Math.round(16 * dp));
		swatch.setColor(android.graphics.Color.rgb(channel(0), channel(1),
				channel(2)));
		return swatch;
	}

	private int channel(int i) {
		return Math.round(255 * Math.max(0f, Math.min(1f,
				partValue(i).floatValue())));
	}

	/** Close the bar without telling the session: it ended. */
	public void close() {
		tuned = null;
		session = null;
		setConstant(false);
		setBuilding(false);
		SoftKeyboard.hide(getContext(), valueView);
		valueView.clearFocus();
		// Fades and sinks 16 dp, emphasized accelerate, 150 ms.
		animate().cancel();
		if (animates() && getVisibility() == VISIBLE) {
			animate().alpha(0f)
					.translationY(16 * getResources().getDisplayMetrics().density)
					.setDuration(150)
					.setInterpolator(new android.view.animation.PathInterpolator(
							0.3f, 0f, 0.8f, 0.15f))
					.withEndAction(() -> {
						if (tuned == null) {
							setVisibility(GONE);
						}
						setAlpha(1f);
						setTranslationY(0f);
					}).start();
		} else {
			setVisibility(GONE);
		}
	}

	/**
	 * Say that GLSL needs a constant where the number is: the picture
	 * follows when the finger rests.
	 */
	public void setConstant(boolean constant) {
		findViewById(R.id.tuning_note).setVisibility(
				constant ? VISIBLE : GONE);
	}

	/** Show the progress line while a build of the number runs. */
	public void setBuilding(boolean building) {
		com.google.android.material.progressindicator.LinearProgressIndicator
				progress = findViewById(R.id.tuning_building);
		if (building) {
			progress.show();
		} else {
			progress.hide();
		}
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
		// What was typed counts only when it is not what the field shows
		// of the value anyway.
		if (valueView.hasFocus() && !valueView.getText().toString().equals(
				shownValue(now))) {
			takeTyped();
			now = tuned;
		}
		close();
		ending.end(keep && now.changed());
	}

	// Lets the value field go without taking what was typed, so that it
	// shows the value again.
	private void dropTyping() {
		if (valueView.hasFocus()) {
			SoftKeyboard.hide(getContext(), valueView);
			valueView.clearFocus();
			valueView.setError(null);
			showValue();
		}
	}

	private void move(long steps) {
		dropTyping();
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
		ruler.sendAccessibilityEvent(android.view.accessibility
				.AccessibilityEvent.TYPE_VIEW_SELECTED);
		showValue();
		showLine();
		if (tuned.place().vector() != null) {
			showParts();
		}
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
			valueView.setText(shownValue(now));
		}
	}

	// The value as the field shows it: with its sign, without the
	// parentheses a negative one has after a binary operator.
	@NonNull
	private static String shownValue(@NonNull Tuned tuned) {
		return tuned.text().replace("(", "").replace(")", "");
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
