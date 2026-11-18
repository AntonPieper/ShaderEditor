package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.TextAppearanceSpan;
import android.util.AttributeSet;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;

/**
 * The name the caret rests in, in the strip: its form, cut at its end
 * with "…" where it is too long. It looks as what explains does in the
 * strip: the form grey, the name in it bright, no syntax colours; see
 * the style StripInfo and its text appearance Bright.
 */
public final class NameView extends AppCompatTextView {
	/**
	 * What the strip shows of a name.
	 *
	 * @param word         the name
	 * @param form         how it is declared, "disc(p, center, radius)"
	 * @param spoken       what TalkBack says for the form
	 * @param open         what a tap on the form does: Look up
	 * @param action       the label of the one action, or null for none
	 * @param icon         the action's icon
	 * @param actionSpoken what TalkBack says for it
	 * @param act          what it does
	 */
	public record Name(@NonNull String word, @NonNull String form,
			@NonNull String spoken, @NonNull Runnable open,
			@Nullable String action, @DrawableRes int icon,
			@Nullable String actionSpoken, @Nullable Runnable act) {
	}

	private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]+");

	@NonNull
	private String word = "";
	@NonNull
	private String form = "";

	public NameView(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		setTypeface(ShaderEditorApp.preferences.getFont());
		setSingleLine(true);
	}

	/** Show form of word. */
	public void setForm(@NonNull String word, @NonNull String form) {
		this.word = word;
		this.form = form;
		update();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		// What fits depends on the width; set it once the layout is done.
		post(this::update);
	}

	private void update() {
		SpannableStringBuilder sb = new SpannableStringBuilder();
		int room = getWidth() - getTotalPaddingLeft() - getTotalPaddingRight();
		sb.append(room <= 0 ? form : TextUtils.ellipsize(form, getPaint(),
				room, TextUtils.TruncateAt.END));
		// The name, where it is first written whole, is what matters.
		Matcher m = WORD.matcher(form);
		while (m.find()) {
			if (m.group().equals(word)) {
				sb.setSpan(new TextAppearanceSpan(getContext(),
								R.style.TextAppearance_StripInfo_Bright),
						Math.min(m.start(), sb.length()),
						Math.min(m.end(), sb.length()),
						Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
				break;
			}
		}
		setText(sb);
	}
}
