package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

import de.markusfisch.android.shadereditor.R;

/**
 * The rows of the passes sheet, top to bottom in drawing order, with
 * the rail drawn beside their pictures as {@link PassRail} laid it out:
 * lines 1.6 dp, solid for this frame, dashed for the last frame, a badge
 * on a picture that reads its own last frame. Each row holds its
 * picture as the view {@code R.id.pass_picture}; the rail is drawn from
 * where those are, so a row that grows moves nothing it does not have
 * to.
 */
public class PassList extends LinearLayout {
	private final float dp;
	private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint dashed = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint badgeFill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Drawable history;
	private final Path path = new Path();
	private final RectF rect = new RectF();
	private final RectF other = new RectF();
	private final Rect bounds = new Rect();
	@Nullable
	private PassRail.Layout layout;

	public PassList(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		setOrientation(VERTICAL);
		setWillNotDraw(false);
		dp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1,
				getResources().getDisplayMetrics());
		int color = MaterialColors.getColor(this,
				com.google.android.material.R.attr.colorOnSurfaceVariant);
		for (Paint paint : new Paint[]{line, dashed}) {
			paint.setStyle(Paint.Style.STROKE);
			paint.setStrokeWidth(1.6f * dp);
			paint.setStrokeJoin(Paint.Join.ROUND);
			paint.setStrokeCap(Paint.Cap.ROUND);
			paint.setColor(color);
		}
		dashed.setPathEffect(new DashPathEffect(new float[]{4 * dp, 3 * dp}, 0));
		badgeFill.setColor(ColorUtils.setAlphaComponent(MaterialColors.getColor(
				this, com.google.android.material.R.attr.colorSurface), 230));
		history = AppCompatResources.getDrawable(context, R.drawable.ic_history)
				.mutate();
		history.setTint(MaterialColors.getColor(this,
				com.google.android.material.R.attr.colorOnSurface));
	}

	/** Draw the rail of layout beside the rows; none for null. */
	public void setRail(@Nullable PassRail.Layout layout) {
		this.layout = layout;
		invalidate();
	}

	/**
	 * A key for a line of text: the badge, the dashed arrow, or both,
	 * as they are on screen.
	 */
	@NonNull
	public Drawable key(boolean badge, boolean dashed) {
		return new Key(badge, dashed);
	}

	@Override
	protected void dispatchDraw(@NonNull Canvas canvas) {
		super.dispatchDraw(canvas);
		var layout = this.layout;
		if (layout == null) {
			return;
		}
		for (var read : layout.lines()) {
			var r = read.read();
			if (!picture(r.producer(), rect) || !picture(r.reader(), other)) {
				continue;
			}
			switch (read.form()) {
				case BADGE -> badge(canvas, other.right, other.bottom);
				case ARROW -> {
					float x = (layout.pictureLeft() + PassRail.PICTURE / 2) * dp;
					float top = rect.bottom + 3 * dp;
					float bottom = other.top - 2 * dp;
					path.reset();
					path.moveTo(x, top);
					path.lineTo(x, bottom);
					headDown(x, bottom);
					canvas.drawPath(path, line);
				}
				case LANE -> {
					float x = layout.laneX(read.lane()) * dp;
					float y1 = PassRail.portY(read.producerPort(),
							read.producerPorts(), rect.top / dp,
							rect.bottom / dp) * dp;
					float y2 = PassRail.portY(read.readerPort(),
							read.readerPorts(), other.top / dp,
							other.bottom / dp) * dp;
					float corner = Math.signum(y2 - y1) * 4 * dp;
					float end = other.left - 2 * dp;
					path.reset();
					path.moveTo(rect.left - 2 * dp, y1);
					path.lineTo(x + 4 * dp, y1);
					path.quadTo(x, y1, x, y1 + corner);
					path.lineTo(x, y2 - corner);
					path.quadTo(x, y2, x + 4 * dp, y2);
					path.lineTo(end, y2);
					canvas.drawPath(path, r.last() ? dashed : line);
					path.reset();
					path.moveTo(end - 6 * dp, y2 - 4 * dp);
					path.lineTo(end, y2);
					path.lineTo(end - 6 * dp, y2 + 4 * dp);
					canvas.drawPath(path, line);
				}
				default -> {
				}
			}
		}
	}

	// Where the picture of row is, in this view; false when there is no
	// such row.
	private boolean picture(int row, @NonNull RectF into) {
		if (row < 0 || row >= getChildCount()) {
			return false;
		}
		View child = getChildAt(row);
		View picture = child.findViewById(R.id.pass_picture);
		if (picture == null) {
			return false;
		}
		// The picture sits in a frame in the row: where it really is.
		bounds.set(0, 0, picture.getWidth(), picture.getHeight());
		offsetDescendantRectToMyCoords(picture, bounds);
		into.set(bounds);
		return true;
	}

	private void headDown(float x, float y) {
		path.moveTo(x - 4 * dp, y - 6 * dp);
		path.lineTo(x, y);
		path.lineTo(x + 4 * dp, y - 6 * dp);
	}

	// 14 dp in the picture's bottom right corner, which is rounded.
	private void badge(@NonNull Canvas canvas, float right, float bottom) {
		float size = 14 * dp;
		float r = 4 * dp;
		float rb = 6 * dp;
		path.reset();
		rect.set(right - size, bottom - size, right, bottom);
		path.addRoundRect(rect, new float[]{r, r, 0, 0, rb, rb, 0, 0},
				Path.Direction.CW);
		canvas.drawPath(path, badgeFill);
		int icon = Math.round(12 * dp);
		int left = Math.round(right - size + (size - icon) / 2);
		int top = Math.round(bottom - size + (size - icon) / 2);
		history.setBounds(left, top, left + icon, top + icon);
		history.draw(canvas);
	}

	// The badge, then a dashed arrow of 28 dp.
	private final class Key extends Drawable {
		private final boolean badge;
		private final boolean arrow;

		Key(boolean badge, boolean arrow) {
			this.badge = badge;
			this.arrow = arrow;
		}

		@Override
		public int getIntrinsicWidth() {
			return Math.round((badge && arrow ? 50 : badge ? 14 : 28) * dp);
		}

		@Override
		public int getIntrinsicHeight() {
			return Math.round(14 * dp);
		}

		@Override
		public void draw(@NonNull Canvas canvas) {
			var bounds = getBounds();
			if (badge) {
				badge(canvas, bounds.left + 14 * dp, bounds.bottom);
			}
			if (!arrow) {
				return;
			}
			float y = bounds.exactCenterY();
			float end = bounds.right;
			float start = end - 28 * dp;
			path.reset();
			path.moveTo(start, y);
			path.lineTo(end, y);
			canvas.drawPath(path, dashed);
			path.reset();
			path.moveTo(end - 6 * dp, y - 4 * dp);
			path.lineTo(end, y);
			path.lineTo(end - 6 * dp, y + 4 * dp);
			canvas.drawPath(path, line);
		}

		@Override
		public void setAlpha(int alpha) {
		}

		@Override
		public void setColorFilter(@Nullable ColorFilter colorFilter) {
		}

		@Override
		public int getOpacity() {
			return PixelFormat.TRANSLUCENT;
		}
	}
}
