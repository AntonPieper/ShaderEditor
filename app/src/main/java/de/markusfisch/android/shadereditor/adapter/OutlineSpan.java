package de.markusfisch.android.shadereditor.adapter;

import android.graphics.Paint;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.UpdateAppearance;

import androidx.annotation.NonNull;

/**
 * Thickens the regular face with a stroke in the text colour. Not metric
 * affecting, so the advances stay those of the regular glyphs.
 */
final class OutlineSpan extends CharacterStyle implements UpdateAppearance {
	private static final float STROKE_PER_TEXT_SIZE = 0.04f;

	static float strokeWidth(float textSize) {
		return textSize * STROKE_PER_TEXT_SIZE;
	}

	@Override
	public void updateDrawState(@NonNull TextPaint paint) {
		paint.setStyle(Paint.Style.FILL_AND_STROKE);
		paint.setStrokeWidth(strokeWidth(paint.getTextSize()));
	}
}
