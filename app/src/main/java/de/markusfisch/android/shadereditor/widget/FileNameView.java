package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import de.markusfisch.android.shadereditor.navigation.NameCut;

/**
 * A file's name on one line, shortened where it does not fit so that
 * its ending stays ({@link NameCut}): "A very long sketch na….frag".
 * Set the name with {@link #setName}; text set otherwise is shown as a
 * text view shows it. TalkBack hears the whole name.
 */
public class FileNameView extends AppCompatTextView {
	@Nullable
	private String name;

	public FileNameView(@NonNull Context context) {
		super(context);
	}

	public FileNameView(@NonNull Context context,
			@Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	public FileNameView(@NonNull Context context,
			@Nullable AttributeSet attrs, int defStyleAttr) {
		super(context, attrs, defStyleAttr);
	}

	/** Show name, cut to the room there is as the class says. */
	public void setName(@Nullable CharSequence name) {
		super.setText(name, BufferType.NORMAL);
		// After the text, which forgets the name of text set otherwise.
		this.name = name != null ? name.toString() : null;
		requestLayout();
	}

	@Override
	public void setText(CharSequence text, BufferType type) {
		// Text that is not a name is not cut.
		name = null;
		super.setText(text, type);
	}

	@Override
	public void onInitializeAccessibilityNodeInfo(
			@NonNull AccessibilityNodeInfo info) {
		super.onInitializeAccessibilityNodeInfo(info);
		if (name != null) {
			info.setText(name);
		}
	}

	@Override
	protected void onMeasure(int widthSpec, int heightSpec) {
		String whole = name;
		if (whole != null &&
				MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) {
			// Rounded down, so that what is measured to fit is never
			// cut once more by the view's own ellipsis.
			float room = MeasureSpec.getSize(widthSpec) -
					getCompoundPaddingLeft() - getCompoundPaddingRight() - 1;
			String shown = NameCut.cut(whole,
					text -> getPaint().measureText(text) <= room);
			if (!TextUtils.equals(shown, getText())) {
				super.setText(shown, BufferType.NORMAL);
				name = whole;
			}
		}
		super.onMeasure(widthSpec, heightSpec);
	}
}
