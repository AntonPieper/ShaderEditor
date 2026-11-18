package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.TextAppearanceSpan;
import android.text.style.TypefaceSpan;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.color.MaterialColors;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

/**
 * The call being written, in the strip behind its keys: the name called
 * and the names of its parameters, the one the caret is in in the
 * accent. It ends at the end of the strip, so the closing parenthesis
 * never moves; what does not fit is cut from the left, down to the
 * current parameter, and nothing is shown where fewer than six
 * characters fit.
 */
public final class SignatureView extends AppCompatTextView {
	/**
	 * A call being written.
	 *
	 * @param name       the function called
	 * @param parameters the names of its parameters
	 * @param argument   which of them the caret is in, from 0; past the
	 *                   last when the call has too many arguments
	 */
	public record Signature(@NonNull String name,
			@NonNull List<String> parameters, int argument) {
		/** Return the call as it is written: "mix(x, y, a)". */
		@NonNull
		public String text() {
			return name + "(" + String.join(", ", parameters) + ")";
		}

		/** Return where the current parameter starts in text, or -1. */
		public int start() {
			if (argument >= parameters.size()) {
				return -1;
			}
			int start = name.length() + 1;
			for (int i = 0; i < argument; ++i) {
				start += parameters.get(i).length() + 2;
			}
			return start;
		}
	}

	/** The fewest characters that are worth showing. */
	static final int MIN_CHARS = 6;
	private static final String CUT = "…";

	@Nullable
	private Signature signature;

	public SignatureView(@NonNull Context context,
			@Nullable AttributeSet attrs) {
		super(context, attrs);
		setTypeface(ShaderEditorApp.preferences.getFont());
		setSingleLine(true);
	}

	/** Show signature, or nothing for null. */
	public void setSignature(@Nullable Signature signature) {
		this.signature = signature;
		update();
	}

	@Nullable
	public Signature getSignature() {
		return signature;
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		// The text depends on the width; set it once the layout is done.
		post(this::update);
	}

	private void update() {
		Signature shown = signature;
		if (shown == null) {
			setText(null);
			setContentDescription(null);
			return;
		}
		TextPaint paint = getPaint();
		float character = paint.measureText("m");
		Shown fits = character <= 0 ? null : fit(shown, (int) ((getWidth() -
				getPaddingLeft() - getPaddingRight()) / character));
		setContentDescription(description(getContext(), shown));
		if (fits == null) {
			setText(null);
			return;
		}
		SpannableStringBuilder sb = new SpannableStringBuilder(fits.text());
		int quiet = MaterialColors.getColor(this,
				com.google.android.material.R.attr.colorOnSurfaceVariant);
		for (int i = fits.text().indexOf(CUT); i >= 0;
				i = fits.text().indexOf(CUT, i + 1)) {
			// A cut is no code: the app's face, a quieter colour.
			sb.setSpan(new TypefaceSpan("sans-serif"), i, i + 1,
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
			sb.setSpan(new ForegroundColorSpan(quiet), i, i + 1,
					Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		// It explains: the current parameter bright in the grey form.
		if (fits.to() > fits.from()) {
			sb.setSpan(new TextAppearanceSpan(getContext(),
							R.style.TextAppearance_StripInfo_Bright),
					fits.from(), fits.to(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		setText(sb);
	}

	/** What is shown of a call, and where its current parameter is. */
	record Shown(@NonNull String text, int from, int to) {
	}

	/**
	 * Return what of signature is shown where room characters fit, or
	 * null where fewer than {@link #MIN_CHARS} do. The closing
	 * parenthesis is always shown, at the end. A call too long is cut
	 * from the left; where that would cut the current parameter away,
	 * the parameter is kept and what follows it is cut instead.
	 */
	@Nullable
	static Shown fit(@NonNull Signature signature, int room) {
		String text = signature.text();
		int length = text.length();
		int start = signature.start();
		int end = start < 0 ? -1 : start +
				signature.parameters().get(signature.argument()).length();
		if (room < MIN_CHARS) {
			return null;
		} else if (length <= room) {
			return new Shown(text, Math.max(0, start), Math.max(0, end));
		}
		int from = length - (room - 1);
		if (start < 0 || from <= start) {
			return new Shown(CUT + text.substring(from),
					start < 0 ? 0 : start - from + 1,
					start < 0 ? 0 : end - from + 1);
		}
		// The current parameter, as much of it as fits, and the end.
		String parameter = text.substring(start,
				Math.min(end, start + room - 3));
		return new Shown(CUT + parameter + CUT + ")", 1,
				1 + parameter.length());
	}

	/** What TalkBack says for signature. */
	@NonNull
	public static String description(@NonNull Context context,
			@NonNull Signature signature) {
		int count = signature.parameters().size();
		int index = Math.min(signature.argument(), count - 1);
		return context.getString(R.string.a11y_signature, signature.name(),
				signature.argument() + 1, count,
				index < 0 ? "" : signature.parameters().get(index));
	}
}
