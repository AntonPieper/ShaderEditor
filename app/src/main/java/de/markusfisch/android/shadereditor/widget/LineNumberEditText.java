package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.Layout;
import android.util.AttributeSet;
import android.view.ViewTreeObserver;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatEditText;

import com.google.android.material.color.MaterialColors;

import de.markusfisch.android.shadereditor.R;

public class LineNumberEditText extends AppCompatEditText {
	private final float lineNumberSpacing;
	private final float lineNumberPadding;

	private final Rect visibleRect = new Rect();
	// Cancel a frame whose padding is stale; it is drawn again after
	// the new layout.
	private final ViewTreeObserver.OnPreDrawListener updatePaddingBeforeDraw =
			() -> !updateTextPadding();
	private final ViewTreeObserver.OnScrollChangedListener redrawNumbersOnScroll =
			this::redrawNumbersIfScrolledOut;

	private boolean showLineNumbers;
	private Paint lineNumberPaint;
	private float bigChar;
	private int paddingLeft;
	// The vertical range drawn by the last onDraw(), in view coordinates.
	private int drawnTop;
	private int drawnBottom;

	public LineNumberEditText(Context context) {
		this(context, null);
	}

	public LineNumberEditText(Context context, AttributeSet attrs) {
		super(context, attrs);
		int lineColor;
		//noinspection resource
		TypedArray a = context.getTheme().obtainStyledAttributes(
				attrs,
				R.styleable.LineNumberEditText,
				0, 0);

		try {
			showLineNumbers = a.getBoolean(
					R.styleable.LineNumberEditText_showLineNumbers,
					true);
			lineColor = a.getColor(
					R.styleable.LineNumberEditText_lineNumberColor,
					MaterialColors.getColor(this, com.google.android.material
							.R.attr.colorOnSurfaceVariant));
			lineNumberSpacing = a.getDimension(
					R.styleable.LineNumberEditText_lineNumberSpacing,
					16);
			lineNumberPadding = a.getDimension(
					R.styleable.LineNumberEditText_lineNumberPadding,
					8);
		} finally {
			a.recycle();
		}
		init(lineColor);
	}

	public void setShowLineNumbers(boolean showLineNumbers) {
		if (showLineNumbers != this.showLineNumbers) {
			this.showLineNumbers = showLineNumbers;
			updateTextPadding();
			postInvalidate();
		}
	}

	private void redrawNumbersIfScrolledOut() {
		if (!showLineNumbers || drawnBottom <= drawnTop ||
				!getLocalVisibleRect(visibleRect)) {
			return;
		}
		int scrollY = getScrollY();
		if (!LineNumberGutter.covers(
				drawnTop,
				drawnBottom,
				visibleRect.top + scrollY,
				visibleRect.bottom + scrollY)) {
			invalidate();
		}
	}

	private void init(int color) {
		paddingLeft = getPaddingLeft();
		lineNumberPaint = new Paint(getPaint());
		lineNumberPaint.setColor(color);
		measureBigChar();
	}

	@Override
	public void setTypeface(@Nullable Typeface tf) {
		super.setTypeface(tf);
		if (lineNumberPaint != null) {
			lineNumberPaint.setTypeface(tf);
			measureBigChar();
			updateTextPadding();
		}
	}

	@Override
	public void setPadding(int left, int top, int right, int bottom) {
		this.paddingLeft = left;
		super.setPadding(left, top, right, bottom);
	}

	@Override
	protected void onTextChanged(
			CharSequence text,
			int start,
			int lengthBefore,
			int lengthAfter) {
		super.onTextChanged(text, start, lengthBefore, lengthAfter);
		// New text (setText()) leaves this view without a layout until
		// the next measure. Setting the padding now makes that the only
		// layout; setting it before the next draw would throw the new
		// layout away and build it again. Edits keep the layout, so
		// they are left to updatePaddingBeforeDraw.
		if (showLineNumbers && lineNumberPaint != null && getLayout() == null) {
			applyTextPaddingLeft(LineNumberGutter.textPaddingLeft(
					true,
					LineNumberGutter.paragraphCount(text),
					bigChar,
					lineNumberPadding,
					lineNumberSpacing,
					paddingLeft));
		}
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		ViewTreeObserver observer = getViewTreeObserver();
		observer.addOnPreDrawListener(updatePaddingBeforeDraw);
		observer.addOnScrollChangedListener(redrawNumbersOnScroll);
	}

	@Override
	protected void onDetachedFromWindow() {
		ViewTreeObserver observer = getViewTreeObserver();
		observer.removeOnPreDrawListener(updatePaddingBeforeDraw);
		observer.removeOnScrollChangedListener(redrawNumbersOnScroll);
		super.onDetachedFromWindow();
	}

	@Override
	protected void onDraw(Canvas canvas) {
		super.onDraw(canvas);
		drawnTop = 0;
		drawnBottom = 0;
		Layout layout = getLayout();
		if (!showLineNumbers || layout == null) {
			return;
		}
		int lineCount = layout.getLineCount();
		if (lineCount < 1) {
			return;
		}
		// Only draw the numbers around the visible part of the text.
		// A ScrollView does not redraw this view when it scrolls, so
		// draw a screen more above and below and redraw only when the
		// visible part leaves that area (see redrawNumbersOnScroll).
		if (getLocalVisibleRect(visibleRect)) {
			int scrollY = getScrollY();
			int margin = visibleRect.height();
			drawnTop = visibleRect.top + scrollY - margin;
			drawnBottom = visibleRect.bottom + scrollY + margin;
		} else if (canvas.getClipBounds(visibleRect)) {
			drawnTop = visibleRect.top;
			drawnBottom = visibleRect.bottom;
		} else {
			return;
		}
		// Offset of the layout inside this view.
		int layoutTop = getLineBounds(0, null) - layout.getLineBaseline(0);
		long range = LineNumberGutter.lineRange(
				lineCount,
				drawnTop - layoutTop,
				drawnBottom - layoutTop,
				layout::getLineForVertical);
		for (int i = LineNumberGutter.first(range),
				last = LineNumberGutter.last(range); i <= last; ++i) {
			int lineNumber = i + 1;
			canvas.drawText(
					Integer.toString(lineNumber),
					LineNumberGutter.numberX(lineNumber, lineCount,
							bigChar, lineNumberPadding),
					layout.getLineBaseline(i) + layoutTop,
					lineNumberPaint);
		}
	}

	/**
	 * Gives the text the left padding the line numbers need.
	 * TextView.setPadding() always invalidates and drops the layout
	 * when the padding changes, so only call it when the padding has
	 * to change, never from onDraw().
	 *
	 * @return true if the padding changed
	 */
	private boolean updateTextPadding() {
		if (lineNumberPaint == null) {
			return false;
		}
		Layout layout = getLayout();
		if (showLineNumbers && layout == null) {
			// The line count is unknown until the next layout.
			return false;
		}
		return applyTextPaddingLeft(LineNumberGutter.textPaddingLeft(
				showLineNumbers,
				layout == null ? 0 : layout.getLineCount(),
				bigChar,
				lineNumberPadding,
				lineNumberSpacing,
				paddingLeft));
	}

	private boolean applyTextPaddingLeft(int left) {
		if (left == getPaddingLeft()) {
			return false;
		}
		super.setPadding(
				left,
				getPaddingTop(),
				getPaddingRight(),
				getPaddingBottom());
		return true;
	}

	private void measureBigChar() {
		bigChar = lineNumberPaint.measureText("m");
	}
}
