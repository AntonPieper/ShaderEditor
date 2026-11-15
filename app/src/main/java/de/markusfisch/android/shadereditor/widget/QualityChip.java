package de.markusfisch.android.shadereditor.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SoundEffectConstants;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatSpinner;

/**
 * The toolbar's frame-rate and quality chip: a spinner of the
 * qualities, whose tap can open something else instead of its list,
 * as the passes sheet does where the shaders are files.
 */
public class QualityChip extends AppCompatSpinner {
	@Nullable
	private Runnable opener;

	public QualityChip(@NonNull Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
	}

	/** Run opener on a tap instead of showing the list; null for the list. */
	public void setOpener(@Nullable Runnable opener) {
		this.opener = opener;
	}

	/** Whether a tap opens something else than the list. */
	public boolean opens() {
		return opener != null;
	}

	@Override
	public void onInitializeAccessibilityNodeInfo(
			@NonNull AccessibilityNodeInfo info) {
		super.onInitializeAccessibilityNodeInfo(info);
		if (opener != null) {
			// It opens something; it is no list to choose from.
			info.setClassName(Button.class.getName());
		}
	}

	@Override
	public boolean performClick() {
		if (opener == null) {
			return super.performClick();
		}
		playSoundEffect(SoundEffectConstants.CLICK);
		sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED);
		opener.run();
		return true;
	}
}
