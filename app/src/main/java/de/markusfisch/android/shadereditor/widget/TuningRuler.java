package de.markusfisch.android.shadereditor.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.color.MaterialColors;

import java.math.BigDecimal;
import java.math.RoundingMode;

import de.markusfisch.android.shadereditor.R;

/**
 * A horizontal ruler without ends, one tick per step: dragging it moves
 * a number by whole steps, 1 : 1 under the finger; a fling runs on and
 * stops on a step. Every tenth tick is long and has its value under it;
 * the centre mark is the value, a ring the value the tuning started
 * at. It draws only when something moved.
 */
public class TuningRuler extends View {
	/** Hears the ruler move the value. */
	public interface Listener {
		/**
		 * Step count steps from where the value is now, as a key or
		 * TalkBack asks; not part of a drag.
		 */
		default void onStep(long count) {
		}

		/** What TalkBack says for the value and where it started. */
		@NonNull
		default CharSequence describe() {
			return "";
		}

		/** The value is count steps from where the drag started. */
		void onSteps(long count);

		/** A drag or fling started on it. */
		default void onDragStart() {
		}

		/** The ruler came to rest. */
		default void onRest() {
		}
	}

	private final Paint minor = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint major = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint centre = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint fade = new Paint();
	// The fades at both ends, made again when the width changes.
	@Nullable
	private Shader fadeLeft;
	@Nullable
	private Shader fadeRight;
	private final float spacing;
	private final float minorHeight;
	private final float majorHeight;
	private final float centreHeight;
	private final float ringRadius;
	private final float fadeWidth;
	private final float labelGap;
	private final int touchSlop;
	private final int minFling;
	private final int maxFling;
	private final OverScroller scroller;
	@Nullable
	private Listener listener;
	@Nullable
	private VelocityTracker velocity;
	// The value as a position in steps, the start's, and the step.
	@NonNull
	private BigDecimal step = BigDecimal.ONE;
	private double position;
	private double startPosition;
	// Where a drag began: its x, the position and the steps told.
	private float downX;
	private double downPosition;
	private long told;
	private boolean dragging;
	// The last position a tick was felt at, at most one per frame, and
	// when.
	private long tickedAt = Long.MIN_VALUE;
	private long tickFrame;
	@Nullable
	private ValueAnimator settle;
	// Where a fling began.
	private double flingBase;

	public TuningRuler(Context context) {
		this(context, null);
	}

	public TuningRuler(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		float dp = getResources().getDisplayMetrics().density;
		spacing = 12 * dp;
		minorHeight = 8 * dp;
		majorHeight = 16 * dp;
		centreHeight = 28 * dp;
		ringRadius = 4 * dp;
		fadeWidth = 32 * dp;
		labelGap = 4 * dp;
		int variant = MaterialColors.getColor(this,
				com.google.android.material.R.attr.colorOnSurfaceVariant);
		int primary = MaterialColors.getColor(this,
				androidx.appcompat.R.attr.colorPrimary);
		minor.setColor(variant);
		minor.setStrokeWidth(dp);
		major.setColor(variant);
		major.setStrokeWidth(1.5f * dp);
		label.setColor(variant);
		label.setTextAlign(Paint.Align.CENTER);
		label.setTextSize(TypedValue.applyDimension(
				TypedValue.COMPLEX_UNIT_SP, 12,
				getResources().getDisplayMetrics()));
		label.setFontFeatureSettings("tnum");
		centre.setColor(primary);
		centre.setStrokeWidth(2 * dp);
		centre.setStrokeCap(Paint.Cap.ROUND);
		ring.setColor(primary);
		ring.setStyle(Paint.Style.STROKE);
		ring.setStrokeWidth(1.5f * dp);
		fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
		ViewConfiguration config = ViewConfiguration.get(context);
		touchSlop = config.getScaledTouchSlop();
		minFling = config.getScaledMinimumFlingVelocity();
		maxFling = config.getScaledMaximumFlingVelocity();
		scroller = new OverScroller(context);
		setLayerType(LAYER_TYPE_HARDWARE, null);
		setFocusable(true);
		// Takes the keys while the bar is open, also in touch mode.
		setFocusableInTouchMode(true);
		setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
		setHapticFeedbackEnabled(true);
	}

	// Whether animations run: "Remove animations" makes them instant.
	private boolean animates() {
		return android.provider.Settings.Global.getFloat(
				getContext().getContentResolver(),
				android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
				1f) > 0f;
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	/**
	 * Show value with step, start being where the tuning started; a
	 * drag in progress is not interrupted.
	 */
	public void show(@NonNull BigDecimal value, @NonNull BigDecimal start,
			@NonNull BigDecimal step) {
		this.step = step;
		startPosition = positionOf(start);
		if (!dragging && scroller.isFinished()) {
			position = positionOf(value);
		}
		invalidate();
	}

	/** Slide to value, as Revert does. */
	public void slideTo(@NonNull BigDecimal value, long millis) {
		stop();
		double to = positionOf(value);
		if (millis <= 0 || !animates()) {
			position = to;
			invalidate();
			return;
		}
		animateTo(to, millis, null);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (!isEnabled()) {
			return false;
		}
		if (velocity == null) {
			velocity = VelocityTracker.obtain();
		}
		velocity.addMovement(event);
		switch (event.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				stop();
				downX = event.getX();
				downPosition = position;
				told = 0;
				dragging = false;
				getParent().requestDisallowInterceptTouchEvent(true);
				return true;
			case MotionEvent.ACTION_MOVE: {
				float dx = event.getX() - downX;
				if (!dragging && Math.abs(dx) > touchSlop) {
					dragging = true;
					downX = event.getX();
					dx = 0;
					if (listener != null) {
						listener.onDragStart();
					}
				}
				if (dragging) {
					position = downPosition - dx / spacing;
					tell(Math.round(position - downPosition));
					invalidate();
				}
				return true;
			}
			case MotionEvent.ACTION_UP:
				velocity.computeCurrentVelocity(1000, maxFling);
				float vx = velocity.getXVelocity();
				recycle();
				if (dragging && Math.abs(vx) > minFling) {
					dragging = false;
					fling(-vx);
				} else {
					if (!dragging) {
						performClick();
					}
					dragging = false;
					settleOnStep();
				}
				return true;
			case MotionEvent.ACTION_CANCEL:
				recycle();
				dragging = false;
				settleOnStep();
				return true;
			default:
				return false;
		}
	}

	@Override
	public void computeScroll() {
		if (scroller.isFinished() || !scroller.computeScrollOffset()) {
			return;
		}
		position = flingBase + scroller.getCurrX() / spacing;
		tell(Math.round(position - downPosition));
		if (scroller.isFinished()) {
			settleOnStep();
		} else {
			postInvalidateOnAnimation();
		}
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		int width = getWidth();
		int height = getHeight();
		float mid = width / 2f;
		// The ticks' foot: the centre mark and the ring above it, the
		// labels under it, the whole centred in the height.
		float content = centreHeight + labelGap + labelHeight();
		float base = (height - content) / 2f + centreHeight;
		int save = canvas.saveLayer(0, 0, width, height, null);
		long first = (long) Math.floor(position - mid / spacing) - 1;
		long last = (long) Math.ceil(position + mid / spacing) + 1;
		for (long i = first; i <= last; ++i) {
			float x = (float) (mid + (i - position) * spacing);
			if (i % 10 == 0) {
				canvas.drawLine(x, base - majorHeight, x, base, major);
				canvas.drawText(labelOf(i), x,
						base + labelGap - label.ascent(), label);
			} else {
				canvas.drawLine(x, base - minorHeight, x, base, minor);
			}
		}
		float ringX = (float) (mid + (startPosition - position) * spacing);
		if (ringX > -ringRadius && ringX < width + ringRadius) {
			canvas.drawCircle(ringX, base - majorHeight - ringRadius, ringRadius,
					ring);
		}
		canvas.drawLine(mid, base - centreHeight, mid, base, centre);
		// The ends fade out.
		fade.setShader(fadeLeft);
		canvas.drawRect(0, 0, fadeWidth, height, fade);
		fade.setShader(fadeRight);
		canvas.drawRect(width - fadeWidth, 0, width, height, fade);
		canvas.restoreToCount(save);
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		fadeLeft = new LinearGradient(0, 0, fadeWidth, 0, 0xff000000,
				0x00000000, Shader.TileMode.CLAMP);
		fadeRight = new LinearGradient(w - fadeWidth, 0, w, 0, 0x00000000,
				0xff000000, Shader.TileMode.CLAMP);
	}

	@Override
	public boolean performClick() {
		return super.performClick();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int height = (int) Math.ceil(Math.max(48 *
						getResources().getDisplayMetrics().density,
				2 * ringRadius + centreHeight + labelGap + labelHeight()));
		setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(),
						widthMeasureSpec),
				resolveSize(height, heightMeasureSpec));
	}

	private float labelHeight() {
		return label.descent() - label.ascent();
	}

	private double positionOf(@NonNull BigDecimal value) {
		return value.divide(step, 12, RoundingMode.HALF_EVEN).doubleValue();
	}

	@NonNull
	private String labelOf(long tick) {
		BigDecimal value = step.multiply(BigDecimal.valueOf(tick));
		BigDecimal stripped = value.stripTrailingZeros();
		return (stripped.scale() < 0 ? stripped.setScale(0) : stripped)
				.toPlainString();
	}

	private void tell(long count) {
		if (count != told) {
			told = count;
			if (listener != null) {
				listener.onSteps(count);
			}
			tick();
		}
	}

	// A light tick per step while dragging, at most one per frame; no
	// ticks while flinging, one when it settles; a firmer one passing
	// the start. performHapticFeedback follows the touch-feedback
	// setting, and returns at once.
	private void tick() {
		if (!dragging) {
			return;
		}
		long step = Math.round(position);
		long frame = android.os.SystemClock.uptimeMillis() / 16;
		if (step == tickedAt || frame == tickFrame) {
			return;
		}
		boolean start = Math.round(startPosition) == step;
		tickedAt = step;
		tickFrame = frame;
		performHapticFeedback(start
				? (android.os.Build.VERSION.SDK_INT >= 34
				? android.view.HapticFeedbackConstants.SEGMENT_TICK
				: android.view.HapticFeedbackConstants.CONTEXT_CLICK)
				: android.view.HapticFeedbackConstants.CLOCK_TICK);
	}

	@Override
	public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
		int sign = keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT
				? -1
				: keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
		if (sign == 0 || listener == null) {
			return super.onKeyDown(keyCode, event);
		}
		// Shift: ten steps.
		listener.onStep(sign * (event.isShiftPressed() ? 10L : 1L));
		return true;
	}

	@Override
	public void onInitializeAccessibilityNodeInfo(
			@NonNull android.view.accessibility.AccessibilityNodeInfo info) {
		super.onInitializeAccessibilityNodeInfo(info);
		// One adjustable node: a slider whose value is spoken.
		info.setClassName("android.widget.SeekBar");
		info.setRangeInfo(android.view.accessibility.AccessibilityNodeInfo
				.RangeInfo.obtain(android.view.accessibility
						.AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
						Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY,
						(float) position));
		if (listener != null) {
			// The value spoken: a state where the platform has one, the
			// text before that.
			if (android.os.Build.VERSION.SDK_INT >= 30) {
				info.setStateDescription(listener.describe());
				// A name of its own: a slider of nothing but a state is
				// not touched by TalkBack 17.
				info.setContentDescription(getContext().getString(
						R.string.tuning_value));
			} else {
				info.setText(listener.describe());
			}
		}
		info.addAction(android.view.accessibility.AccessibilityNodeInfo
				.AccessibilityAction.ACTION_SCROLL_FORWARD);
		info.addAction(android.view.accessibility.AccessibilityNodeInfo
				.AccessibilityAction.ACTION_SCROLL_BACKWARD);
		// TalkBack adjusts a node with a range by setting its progress.
		if (android.os.Build.VERSION.SDK_INT >= 24) {
			info.addAction(android.view.accessibility.AccessibilityNodeInfo
					.AccessibilityAction.ACTION_SET_PROGRESS);
		}
	}

	@Override
	public boolean performAccessibilityAction(int action,
			@Nullable android.os.Bundle arguments) {
		if (listener != null && (action == android.view.accessibility
				.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
				action == android.view.accessibility.AccessibilityNodeInfo
						.ACTION_SCROLL_BACKWARD)) {
			listener.onStep(action == android.view.accessibility
					.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
			return true;
		}
		if (listener != null && arguments != null &&
				android.os.Build.VERSION.SDK_INT >= 24 &&
				action == android.R.id.accessibilityActionSetProgress) {
			// The range has no ends: a progress asked for is a step towards
			// it.
			float to = arguments.getFloat(android.view.accessibility
					.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE);
			if (Float.isNaN(to) || to == (float) position) {
				return false;
			}
			listener.onStep(to > position ? 1 : -1);
			return true;
		}
		return super.performAccessibilityAction(action, arguments);
	}

	private void fling(float velocityX) {
		flingBase = position;
		scroller.fling(0, 0, Math.round(velocityX), 0,
				Integer.MIN_VALUE, Integer.MAX_VALUE, 0, 0);
		postInvalidateOnAnimation();
	}

	private void settleOnStep() {
		double to = downPosition + told;
		if (!animates()) {
			position = to;
			invalidate();
			rest();
			return;
		}
		animateTo(to, 100, this::rest);
	}

	// Moves the ruler to the position to, in doubles: a float would
	// lose the steps of a large number.
	private void animateTo(double to, long millis, @Nullable Runnable done) {
		double from = position;
		settle = ValueAnimator.ofFloat(0f, 1f);
		settle.setDuration(millis);
		settle.addUpdateListener(a -> {
			position = from + (to - from) * a.getAnimatedFraction();
			invalidate();
		});
		if (done != null) {
			settle.addListener(new android.animation.AnimatorListenerAdapter() {
				@Override
				public void onAnimationEnd(android.animation.Animator animation) {
					done.run();
				}
			});
		}
		settle.start();
	}

	private void rest() {
		if (listener != null) {
			listener.onRest();
		}
	}

	private void stop() {
		if (settle != null) {
			settle.cancel();
			settle = null;
		}
		if (!scroller.isFinished()) {
			scroller.forceFinished(true);
			// What the fling reached stays.
			position = downPosition + told;
		}
	}

	private void recycle() {
		if (velocity != null) {
			velocity.recycle();
			velocity = null;
		}
	}
}
