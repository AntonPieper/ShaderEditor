package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Buttons side by side at the end of a row, or one under another, each
 * at the end, when they do not fit side by side with their whole text,
 * as a dialog's buttons are stacked.
 */
public final class ButtonRow extends LinearLayout {
	public ButtonRow(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		setGravity(Gravity.END);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		setOrientation(fits(widthMeasureSpec) ? HORIZONTAL : VERTICAL);
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
	}

	private boolean fits(int widthMeasureSpec) {
		if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
			return true;
		}
		int wanted = getPaddingLeft() + getPaddingRight();
		int unspecified = MeasureSpec.makeMeasureSpec(0,
				MeasureSpec.UNSPECIFIED);
		for (int i = 0, n = getChildCount(); i < n; ++i) {
			View child = getChildAt(i);
			if (child.getVisibility() == GONE) {
				continue;
			}
			child.measure(unspecified, unspecified);
			LayoutParams params = (LayoutParams) child.getLayoutParams();
			wanted += child.getMeasuredWidth() + params.leftMargin +
					params.rightMargin;
		}
		return wanted <= MeasureSpec.getSize(widthMeasureSpec);
	}
}
