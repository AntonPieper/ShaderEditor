package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.TextPaint;
import android.text.style.TextAppearanceSpan;
import android.util.TypedValue;

import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.highlighter.Highlight;
import de.markusfisch.android.shadereditor.syntax.Style;

/**
 * Every colour, size and stroke of a CodeView, read from resources and
 * the theme in this one place, and the metrics of its font. Changing the
 * font or a size invalidates every LineLayout made before.
 */
final class CodeAppearance {
	private static final String PRINTABLE;

	static {
		StringBuilder sb = new StringBuilder();
		for (char c = 0x20; c < 0x7f; ++c) {
			sb.append(c);
		}
		PRINTABLE = sb.toString();
	}

	final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	final int[] styleColors = new int[Style.values().length];
	final int textColor;
	final int lineNumberColor;
	final int errorColor;
	final int warningColor;
	// Of the bar and the number of a line with an error.
	final int markColor;
	final float markWidth;
	// Of the row under a line that shows the messages of its errors,
	// whose text is in markColor.
	final TextPaint rowPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	final int rowColor;
	// Of the fix a row offers, a text button on a line of its own: its
	// label, its fill while pressed and how high that is, the height of
	// its line and where the label's baseline is in it, the space
	// between it and the text above, and the space beside its label.
	final TextPaint fixPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
	final int fixPressedColor;
	final float fixPressedHeight;
	final int fixHeight;
	final float fixBaseline;
	final int fixInset;
	final float fixPadding;
	// Between two fixes side by side, and between two lines of fixes
	// when they do not fit side by side.
	final float fixGap;
	final int fixLineGap;
	// Of things that float, such as the shadow of dragged text, and the
	// hairline around them.
	final int cardColor;
	final int cardOutlineColor;
	final float hairline;
	final float rowPadding;
	final int rowPaddingVertical;
	final int rowLineHeight;
	final int rowBaseline;
	// The widest a row is, from where the text of the code starts.
	final float rowMaxWidth;
	final float corner;
	final float tapTarget;
	final int selectionColor;
	final int foundColor;
	final int caretColor;
	final int handleColor;
	final float caretWidth;
	final float underline;
	final float padding;
	int tabWidth = 4;
	// Of the font and its size.
	float charWidth;
	float digitWidth;
	int lineHeight;
	int baseline;
	// Whether every printable ASCII character has the same width.
	boolean monospace;
	// MonoGlyphs from Android 12 on, if the font allows; null else.
	Object glyphs;

	CodeAppearance(Context context) {
		Resources res = context.getResources();
		for (Style style : Style.values()) {
			styleColors[style.ordinal()] = ContextCompat.getColor(context,
					Highlight.colorOf(style));
		}
		textColor = ContextCompat.getColor(context, R.color.editor_text);
		lineNumberColor = ContextCompat.getColor(context,
				R.color.editor_line_numbers);
		errorColor = ContextCompat.getColor(context, R.color.syntax_error);
		warningColor = ContextCompat.getColor(context,
				R.color.syntax_warning);
		markColor = MaterialColors.getColor(context,
				androidx.appcompat.R.attr.colorError, errorColor);
		markWidth = res.getDimension(R.dimen.editor_mark_width);
		rowColor = MaterialColors.getColor(context,
				com.google.android.material.R.attr.colorSurface, textColor);
		cardColor = MaterialColors.getColor(context, com.google.android
				.material.R.attr.colorSurfaceContainerHigh, rowColor);
		cardOutlineColor = MaterialColors.getColor(context, com.google
				.android.material.R.attr.colorOutlineVariant, lineNumberColor);
		hairline = res.getDimension(R.dimen.se_hairline);
		TypedValue body = new TypedValue();
		if (context.getTheme().resolveAttribute(com.google.android.material
				.R.attr.textAppearanceBodyMedium, body, true)) {
			new TextAppearanceSpan(context, body.resourceId)
					.updateMeasureState(rowPaint);
		}
		rowPaint.setColor(markColor);
		// Lines of 20 sp, as BodyMedium's, each baseline in the middle of
		// its line; more if the font needs more.
		Paint.FontMetricsInt rowMetrics = rowPaint.getFontMetricsInt();
		int rowText = rowMetrics.descent - rowMetrics.ascent;
		rowLineHeight = Math.max(rowText, res.getDimensionPixelSize(
				R.dimen.editor_row_line_height));
		rowBaseline = (rowLineHeight - rowText) / 2 - rowMetrics.ascent;
		rowPadding = res.getDimension(R.dimen.editor_padding);
		rowPaddingVertical = res.getDimensionPixelSize(
				R.dimen.editor_row_padding_vertical);
		rowMaxWidth = res.getDimension(R.dimen.editor_row_max_width);
		corner = res.getDimension(R.dimen.se_corner);
		tapTarget = res.getDimension(R.dimen.se_tap_target);
		caretColor = ContextCompat.getColor(context, R.color.editor_caret);
		selectionColor = ContextCompat.getColor(context,
				R.color.code_selection);
		foundColor = ContextCompat.getColor(context, R.color.code_match);
		TypedArray a = context.obtainStyledAttributes(new int[]{
				androidx.appcompat.R.attr.colorPrimary});
		try {
			handleColor = a.getColor(0, caretColor);
		} finally {
			a.recycle();
		}
		TypedValue label = new TypedValue();
		if (context.getTheme().resolveAttribute(com.google.android.material
				.R.attr.textAppearanceLabelLarge, label, true)) {
			new TextAppearanceSpan(context, label.resourceId)
					.updateMeasureState(fixPaint);
		}
		fixPaint.setColor(handleColor);
		// 12 % of the accent, as the library's text button when pressed.
		fixPressedColor = (handleColor & 0xffffff) | 0x1f000000;
		// LabelLarge's line of 20 sp, as the row's lines.
		Paint.FontMetricsInt fixMetrics = fixPaint.getFontMetricsInt();
		int fixText = fixMetrics.descent - fixMetrics.ascent;
		fixHeight = Math.max(fixText, res.getDimensionPixelSize(
				R.dimen.editor_fix_height));
		fixBaseline = (fixHeight - fixText) / 2f - fixMetrics.ascent;
		// 32 dp, centred on the label's line: as far below it as the
		// row's padding; more where large text makes the line higher.
		fixPressedHeight = Math.max(res.getDimension(
						R.dimen.editor_fix_pressed_height),
				fixHeight + 2 * rowPaddingVertical);
		fixInset = res.getDimensionPixelSize(R.dimen.editor_fix_inset);
		fixPadding = res.getDimension(R.dimen.editor_fix_padding);
		fixGap = res.getDimension(R.dimen.editor_fix_gap);
		fixLineGap = res.getDimensionPixelSize(R.dimen.editor_fix_line_gap);
		caretWidth = res.getDimension(R.dimen.editor_caret_width);
		underline = res.getDimension(R.dimen.editor_composing_underline);
		padding = res.getDimension(R.dimen.editor_padding);
		paint.setTypeface(Typeface.MONOSPACE);
		paint.setTextSize(res.getDimension(R.dimen.editor_text_size));
		measure();
	}

	/** Lay out a line; runs may be null for no colours. */
	LineLayout layout(String text, LineStyles.Runs runs) {
		return text.length() > LineLayout.LONG ||
				(monospace && Columns.isSimple(text))
				? new MonoLineLayout(text, runs, this)
				: new PlatformLineLayout(text, runs, this);
	}

	void measure() {
		Paint.FontMetricsInt fm = paint.getFontMetricsInt();
		lineHeight = fm.descent - fm.ascent;
		baseline = -fm.ascent;
		float[] widths = new float[PRINTABLE.length()];
		paint.getTextWidths(PRINTABLE, widths);
		charWidth = widths[0];
		monospace = true;
		for (float width : widths) {
			monospace &= Math.abs(width - charWidth) < .01f;
		}
		digitWidth = 0;
		for (int digit = '0'; digit <= '9'; ++digit) {
			digitWidth = Math.max(digitWidth, widths[digit - 0x20]);
		}
		glyphs = monospace && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
				? MonoGlyphs.of(paint, PRINTABLE, charWidth)
				: null;
	}
}
