package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * One of the two worlds the main screen can be in: the shaders are
 * rows of the database, as in release builds, or files of the library
 * and of other folders. Which one is decided once, where the screen is
 * made; from then on the activity asks no more, it tells the screen
 * what happens to it and what the user asks for.
 *
 * <p>A screen owns what is open, the list in the drawer, and everything
 * that is started for them; it is made in {@code onCreate}, hears the
 * activity's lifecycle and lets everything go in {@link #onDestroy}.
 * Call it on the main thread.
 */
public interface ShaderScreen {
	/**
	 * Show what the screen starts with, once the toolbar and the menu
	 * are set up: text another app shared with intent, what state says
	 * was open before the screen was made again, or what was open last.
	 */
	void start(@Nullable Bundle state, @Nullable Intent intent);

	/** Take back what {@link #onSaveInstanceState} kept. */
	void onRestoreInstanceState(@NonNull Bundle state);

	/** Keep what is open, for the screen that is made again. */
	void onSaveInstanceState(@NonNull Bundle state);

	/** The toolbar shows the title it had; correct it where it is stale. */
	void onPostCreate();

	void onStart();

	void onStop();

	void onResume();

	void onPause();

	/** Let everything go; finishing tells whether the screen ends for good. */
	void onDestroy(boolean finishing);

	/** Another app shared or opened text while the screen is there. */
	void onNewIntent(@Nullable Intent intent);
}
