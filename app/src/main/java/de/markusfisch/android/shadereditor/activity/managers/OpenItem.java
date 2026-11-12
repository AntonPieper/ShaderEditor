package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * What the editor of the main screen has open. The activity and its
 * managers ask it for the toolbar's text and let it save, copy, delete,
 * share and install itself, without knowing where it is stored. It
 * tells the activity when its title or second line changed. Every
 * method is called on the main thread.
 */
public interface OpenItem {
	/** What can be done with what is open. */
	enum Action {
		SAVE,
		SAVE_COPY,
		DELETE,
		SHARE,
		WALLPAPER
	}

	/** Return the toolbar's title. */
	@NonNull
	String getTitle();

	/**
	 * Return the toolbar's second line, or null when it has none of its
	 * own and shows the frame rate.
	 */
	@Nullable
	String getSubtitle();

	/** Return whether the text has changes that are not saved. */
	boolean isModified();

	/** Called when the text in the editor was changed. */
	void onTextModified();

	float getQuality();

	/** Set the quality to run at; it is saved with the text. */
	void setQuality(float quality);

	/**
	 * Return whether action can be done now. The methods below do
	 * nothing when it cannot.
	 */
	boolean can(@NonNull Action action);

	/**
	 * Save the text; when not explicit, only if it has unsaved
	 * changes.
	 */
	void save(boolean explicit);

	/** Save the text as a new copy and open the copy. */
	void saveCopy();

	/** Delete it for good and open what comes next. */
	void delete();

	void share();

	/** Return whether it is what the live wallpaper shows. */
	boolean isWallpaper();

	/** Save it and make it what the live wallpaper shows. */
	void setAsWallpaper();
}
