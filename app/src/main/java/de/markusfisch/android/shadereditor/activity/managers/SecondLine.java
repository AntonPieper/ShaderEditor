package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.Nullable;

/**
 * What the toolbar's second line shows: a notice until its time is up,
 * otherwise the line of what is open, otherwise the frame rate. When
 * what is open has a line of its own, the frame rate stands beside the
 * quality instead.
 */
final class SecondLine {
	@Nullable
	private CharSequence own;
	@Nullable
	private CharSequence frameRate;
	@Nullable
	private CharSequence notice;
	private long noticeUntil;
	private boolean rateFirst;
	@Nullable
	private CharSequence paused;

	void setOwn(@Nullable CharSequence own) {
		this.own = own;
	}

	void setFrameRate(@Nullable CharSequence frameRate) {
		this.frameRate = frameRate;
	}

	/** The chip names a pass shown, so the frame rate leads the line. */
	void setRateFirst(boolean rateFirst) {
		this.rateFirst = rateFirst;
	}

	/** Say paused where the frame rate stands; null while it runs. */
	void setPaused(@Nullable CharSequence paused) {
		this.paused = paused;
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
		CharSequence rate = rate();
		if (rateFirst && rate != null && own != null) {
			return rate + " \u00b7 " + own;
		}
		return own != null ? own : rate;
	}

	@Nullable
	CharSequence besideQuality() {
		return own != null && !rateFirst ? rate() : null;
	}

	// No rate without a picture that runs.
	@Nullable
	private CharSequence rate() {
		return frameRate != null && paused != null ? paused : frameRate;
	}
}
