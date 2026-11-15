package de.markusfisch.android.shadereditor.fragment;

import androidx.annotation.Nullable;

/**
 * Whether code is shown over the picture: not hidden with the code
 * button, and not covered because nothing is open. Every change of
 * either is told to one listener, which is told the fact as it is when
 * it starts listening, and then once for each time the fact changes.
 */
public final class CodeShown {
	public interface Listener {
		void onCodeShown(boolean shown);
	}

	private boolean hidden;
	private boolean covered;
	@Nullable
	private Listener listener;
	// What the listener was told last, or null before it was told.
	@Nullable
	private Boolean told;

	public void listen(@Nullable Listener listener) {
		this.listener = listener;
		told = null;
		tell();
	}

	public boolean shown() {
		return !hidden && !covered;
	}

	public boolean hidden() {
		return hidden;
	}

	/** The code button hid the code, or showed it again. */
	public void setHidden(boolean hidden) {
		this.hidden = hidden;
		tell();
	}

	/** Nothing is open, so a cover stands where the code is, or not. */
	public void setCovered(boolean covered) {
		this.covered = covered;
		tell();
	}

	private void tell() {
		boolean shown = shown();
		if (listener != null && (told == null || told != shown)) {
			told = shown;
			listener.onCodeShown(shown);
		}
	}
}
