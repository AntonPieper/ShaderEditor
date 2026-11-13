package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.Nullable;

/**
 * What the toolbar's second line shows: a notice until its time is up,
 * otherwise the line of what is open, otherwise the frame rate.
 */
final class SecondLine {
	@Nullable
	private CharSequence own;
	@Nullable
	private CharSequence frameRate;
	@Nullable
	private CharSequence notice;
	private long noticeUntil;

	void setOwn(@Nullable CharSequence own) {
		this.own = own;
	}

	void setFrameRate(@Nullable CharSequence frameRate) {
		this.frameRate = frameRate;
	}

	void showNotice(@Nullable CharSequence notice, long until) {
		this.notice = notice;
		noticeUntil = until;
	}

	@Nullable
	CharSequence shown(long now) {
		if (now < noticeUntil) {
			return notice;
		}
		return own != null ? own : frameRate;
	}
}
