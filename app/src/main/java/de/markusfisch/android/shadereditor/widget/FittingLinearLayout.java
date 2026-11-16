package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * A LinearLayout that, once measured, lets its {@link Fit} change its
 * children for the sizes found, and measures again when it did: the
 * change and the sizes that follow from it come in one layout, so no
 * child is given a size that is taken back at once.
 */
public class FittingLinearLayout extends LinearLayout {
	/** Changes the children of a measured layout. */
	public interface Fit {
		/**
		 * Change the children of layout for their measured sizes;
		 * return whether any changed.
		 */
		boolean fit(@NonNull FittingLinearLayout layout);
	}

	@Nullable
	private Fit fit;

	public FittingLinearLayout(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public void setFit(@Nullable Fit fit) {
		this.fit = fit;
		requestLayout();
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		if (fit != null && fit.fit(this)) {
			super.onMeasure(widthMeasureSpec, heightMeasureSpec);
		}
	}
}
