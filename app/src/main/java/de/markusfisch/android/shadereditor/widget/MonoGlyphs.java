package de.markusfisch.android.shadereditor.widget;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.fonts.Font;
import android.graphics.text.PositionedGlyphs;
import android.graphics.text.TextRunShaper;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

/**
 * The glyph of each printable ASCII character of a font in which shaping
 * them changes nothing: one glyph each, from one font, one width apart.
 * A run of them is then drawn as glyphs at their columns, without the
 * text shaper.
 */
@RequiresApi(31)
final class MonoGlyphs {
	private final Font font;
	private final int[] ids;
	private int[] glyphs = new int[64];
	private float[] positions = new float[128];

	private MonoGlyphs(Font font, int[] ids) {
		this.font = font;
		this.ids = ids;
	}

	/**
	 * Return the glyphs of the characters from 0x20 in printable, or null
	 * if a run of them could shape differently: with font features other
	 * than contextual alternates off (ligatures), variations, or a font
	 * that does not give one glyph per character at charWidth.
	 */
	@Nullable
	static MonoGlyphs of(Paint paint, String printable, float charWidth) {
		String features = paint.getFontFeatureSettings();
		if ((features != null && !"calt off".equals(features)) ||
				paint.getFontVariationSettings() != null) {
			return null;
		}
		int count = printable.length();
		PositionedGlyphs shaped = TextRunShaper.shapeTextRun(printable,
				0, count, 0, count, 0, 0, false, paint);
		if (shaped.glyphCount() != count) {
			return null;
		}
		Font font = shaped.getFont(0);
		int[] ids = new int[count];
		for (int i = 0; i < count; ++i) {
			if (!font.equals(shaped.getFont(i)) ||
					shaped.getGlyphY(i) != 0 ||
					Math.abs(shaped.getGlyphX(i) - i * charWidth) > .01f) {
				return null;
			}
			ids[i] = shaped.getGlyphId(i);
		}
		return new MonoGlyphs(font, ids);
	}

	/** Draw the characters [start, end) of text from x, one per column. */
	void draw(Canvas canvas, String text, int start, int end, float x,
			float y, float charWidth, Paint paint) {
		int count = end - start;
		if (glyphs.length < count) {
			glyphs = new int[count];
			positions = new float[count * 2];
		}
		for (int i = 0; i < count; ++i) {
			glyphs[i] = ids[text.charAt(start + i) - 0x20];
			positions[i * 2] = x + i * charWidth;
			positions[i * 2 + 1] = y;
		}
		canvas.drawGlyphs(glyphs, 0, positions, 0, count, font, paint);
	}
}
