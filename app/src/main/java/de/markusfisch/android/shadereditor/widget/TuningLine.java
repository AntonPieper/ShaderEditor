package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;

import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.R;

import com.google.android.material.color.MaterialColors;

/**
 * The tuned statement on one line: a number that takes a tap has a
 * dotted line under it, one the session changed a solid line; a tap
 * tells which was nearest. A screen reader reaches each number, the
 * tuned one last. Main thread.
 */
public class TuningLine extends AppCompatTextView {
	public interface OnNumber {
		void onNumber(int index);
	}

	private final Paint dotted = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint solid = new Paint(Paint.ANTI_ALIAS_FLAG);
	// From and to of each number in the text, and whether it changed.
	@NonNull
	private int[] marks = new int[0];
	@NonNull
	private boolean[] changed = new boolean[0];
	@Nullable
	private OnNumber onNumber;
	private final ExploreByTouchHelper reader = new ExploreByTouchHelper(this) {
		@Override
		protected int getVirtualViewAt(float x, float y) {
			int i = nearest(x);
			return i > -1 ? i : HOST_ID;
		}

		@Override
		protected void getVisibleVirtualViews(List<Integer> ids) {
			for (int i = 0; i < changed.length; ++i) {
				ids.add(i);
			}
			// In the line's order: the tuned one is last of the marks.
			Collections.sort(ids, (a, b) -> marks[2 * a] - marks[2 * b]);
		}

		@Override
		protected void onPopulateNodeForVirtualView(int i,
				@NonNull AccessibilityNodeInfoCompat node) {
			Layout layout = getLayout();
			String number = i < changed.length
					? getText().subSequence(marks[2 * i],
							Math.min(marks[2 * i + 1], length())).toString()
					: "";
			node.setContentDescription(getContext().getString(
					i == changed.length - 1
							? R.string.a11y_tuning_open
							: changed[i]
									? R.string.tuning_number_changed
									: R.string.tuning_number_tunable, number));
			node.setBoundsInParent(layout == null || number.isEmpty()
					? new Rect()
					: new Rect((int) x(layout, marks[2 * i]), 0,
							(int) Math.ceil(x(layout, marks[2 * i + 1])),
							getHeight()));
			if (i < changed.length - 1) {
				node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK);
			}
		}

		@Override
		protected boolean onPerformActionForVirtualView(int i, int action,
				@Nullable Bundle arguments) {
			if (action != AccessibilityNodeInfoCompat.ACTION_CLICK ||
					onNumber == null || i >= changed.length - 1) {
				return false;
			}
			onNumber.onNumber(i);
			return true;
		}
	};

	public TuningLine(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		ViewCompat.setAccessibilityDelegate(this, reader);
		float dp = getResources().getDisplayMetrics().density;
		dotted.setStyle(Paint.Style.STROKE);
		dotted.setStrokeWidth(dp);
		dotted.setPathEffect(new DashPathEffect(new float[]{2 * dp, 2 * dp},
				0));
		dotted.setColor(MaterialColors.getColor(this,
				com.google.android.material.R.attr.colorOnSurfaceVariant));
		solid.setStrokeWidth(2 * dp);
		solid.setColor(MaterialColors.getColor(this,
				androidx.appcompat.R.attr.colorPrimary));
	}

	/**
	 * Mark the numbers from marks[2i] to marks[2i + 1] of the text, as
	 * changed[i] says; onNumber hears a tap on one.
	 */
	public void setMarks(@NonNull int[] marks, @NonNull boolean[] changed,
			@Nullable OnNumber onNumber) {
		this.marks = marks;
		this.changed = changed;
		this.onNumber = onNumber;
		invalidate();
		reader.invalidateRoot();
	}

	@Override
	protected boolean dispatchHoverEvent(@NonNull MotionEvent event) {
		return reader.dispatchHoverEvent(event) ||
				super.dispatchHoverEvent(event);
	}

	@Override
	protected void onDraw(@NonNull Canvas canvas) {
		super.onDraw(canvas);
		Layout layout = getLayout();
		if (layout == null) {
			return;
		}
		float y = getBaseline() + 3 * getResources().getDisplayMetrics().density;
		for (int i = 0; i < changed.length; ++i) {
			canvas.drawLine(x(layout, marks[2 * i]), y,
					x(layout, marks[2 * i + 1]), y,
					changed[i] ? solid : dotted);
		}
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		Layout layout = getLayout();
		if (onNumber == null || layout == null || changed.length == 0) {
			return super.onTouchEvent(event);
		}
		if (event.getActionMasked() == MotionEvent.ACTION_UP) {
			int nearest = nearest(event.getX());
			if (nearest > -1) {
				onNumber.onNumber(nearest);
			}
		}
		return true;
	}

	// The number nearest x, as a finger is wider than a digit, or -1.
	private int nearest(float x) {
		Layout layout = getLayout();
		int nearest = -1;
		float best = 24 * getResources().getDisplayMetrics().density;
		for (int i = 0; layout != null && i < changed.length; ++i) {
			float from = x(layout, marks[2 * i]);
			float to = x(layout, marks[2 * i + 1]);
			float distance = x < from ? from - x : x > to ? x - to : 0;
			if (distance <= best) {
				best = distance;
				nearest = i;
			}
		}
		return nearest;
	}

	private float x(@NonNull Layout layout, int offset) {
		return getTotalPaddingLeft() + layout.getPrimaryHorizontal(
				Math.min(offset, length()));
	}
}
