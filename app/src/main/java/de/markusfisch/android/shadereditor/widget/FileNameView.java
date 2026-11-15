package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.appcompat.widget.TooltipCompat;

import de.markusfisch.android.shadereditor.navigation.NameCut;

/**
 * A file's or folder's name on one line, shortened in its middle where
 * it does not fit, its start and its end kept, the ending whole
 * ({@link NameCut}): "A very long sket…at sunset.frag". Set the name
 * with {@link #setName}; text set otherwise is shown as a text view
 * shows it. TalkBack hears the whole name, and where
 * {@link #setWholeOnLongPress} asks for it, a long press shows it.
 */
public class FileNameView extends AppCompatTextView {
	@Nullable
	private String name;
	private boolean wholeOnLongPress;
	// Cut at the end by the view's own ellipsis instead of in the middle.
	private boolean cutsAtEnd;

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

	/** The whole name shown, or null when the text is not a name. */
	@Nullable
	public String getName() {
		return name;
	}

	/**
	 * Cut the name at its end where it does not fit, as the last thing
	 * that gives way in a crowded toolbar, instead of in its middle.
	 */
	public void setCutsAtEnd(boolean atEnd) {
		if (cutsAtEnd != atEnd) {
			cutsAtEnd = atEnd;
			requestLayout();
		}
	}

	/**
	 * Show the whole name in a tooltip on a long press while it is
	 * cut; for a name with no long press of its own, as a title.
	 */
	public void setWholeOnLongPress(boolean whole) {
		wholeOnLongPress = whole;
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
			String shown = cutsAtEnd
					? whole
					: NameCut.cut(whole,
							text -> getPaint().measureText(text) <= room);
			if (!TextUtils.equals(shown, getText())) {
				super.setText(shown, BufferType.NORMAL);
				name = whole;
			}
			if (wholeOnLongPress) {
				TooltipCompat.setTooltipText(this,
						shown.equals(whole) &&
								getPaint().measureText(whole) <= room
								? null
								: whole);
			}
		}
		super.onMeasure(widthSpec, heightSpec);
	}
}
