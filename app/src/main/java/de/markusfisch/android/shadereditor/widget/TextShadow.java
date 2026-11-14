package de.markusfisch.android.shadereditor.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.RectF;
import android.view.View;

/**
 * The shadow of text dragged out of a CodeView: a small card of the
 * colour of floating surfaces, with a hairline so that it stands out on
 * dark code, and on it the start of the text as the
 * view draws it, in its font, size and colours. It draws the view's own
 * layout of the line, clipped to the part shown, so a selection of any
 * size costs the same. The finger holds its middle, as it holds the
 * platform's text shadow.
 */
final class TextShadow extends View.DragShadowBuilder {
	private final LineLayout layout;
	private final float from;
	private final float to;
	private final int width;
	private final int height;
	private final float padding;
	private final float corner;
	private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final RectF bounds = new RectF();

	/** Show line layout from x from to x to, of a line lineHeight tall. */
	TextShadow(LineLayout layout, float from, float to, int lineHeight,
			CodeAppearance appearance) {
		this.layout = layout;
		this.from = from;
		this.to = to;
		padding = appearance.padding;
		corner = appearance.corner;
		card.setColor(appearance.cardColor);
		outline.setColor(appearance.cardOutlineColor);
		outline.setStyle(Paint.Style.STROKE);
		outline.setStrokeWidth(appearance.hairline);
		width = Math.max(1, (int) Math.ceil(to - from + padding * 2));
		height = (int) Math.ceil(lineHeight + padding * 2);
	}

	@Override
	public void onProvideShadowMetrics(Point size, Point touch) {
		size.set(width, height);
		touch.set(width / 2, height / 2);
	}

	@Override
	public void onDrawShadow(Canvas canvas) {
		bounds.set(0, 0, width, height);
		canvas.drawRoundRect(bounds, corner, corner, card);
		float inset = outline.getStrokeWidth() / 2;
		bounds.inset(inset, inset);
		canvas.drawRoundRect(bounds, corner - inset, corner - inset, outline);
		int save = canvas.save();
		canvas.clipRect(padding, padding, width - padding, height - padding);
		canvas.translate(padding - from, padding);
		layout.draw(canvas, from, to);
		canvas.restoreToCount(save);
	}
}
