package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

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
	/** Kept of a screen: whether the code is shown. */
	String CODE_VISIBLE = "code_visible";
	/** How long an explicit save is told in the toolbar's second line. */
	long SAVED_NOTICE_MILLIS = 2000;

	/** What a screen asks of the activity that shows it. */
	interface Host {
		/** Pick the live wallpaper if it is not picked, else say it is set. */
		void showWallpaper();

		/** Forget what an earlier run reported. */
		void clearErrors();

		/**
		 * Run the text in the editor as the shader open: behind the
		 * code, or in the preview that preview launches.
		 */
		void runText(@NonNull OpenItem open,
				@NonNull ActivityResultLauncher<Intent> preview);
	}

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

	/**
	 * Return what is open: what the toolbar shows and what Save, Share
	 * and the other entries of the menu act on.
	 */
	@NonNull
	OpenItem open();

	/** The user asks for a new shader. */
	void newShader();

	/** The user asks for a copy of what is open. */
	void saveCopy();

	/** Delete what is open; the user said yes. */
	void delete();

	/** Set what is open as the wallpaper; it can be one. */
	void updateWallpaper();

	/** Return whether what is open is the wallpaper. */
	boolean isWallpaper();

	/** Show the examples. */
	void showExamples();

	/**
	 * Move the shaders of the database into files once more. Only
	 * offered where the shaders are files.
	 */
	void moveLibraryAgain();

	/** The user asks to run what is open. */
	void run();

	/** The user asks to show or hide the code. */
	void toggleCode();

	/** The user asks to add a uniform to the text. */
	void addUniform();

	/** The user stopped typing; text is what the editor holds. */
	void onEditPaused(@NonNull CharSequence text);

	/** The user chose quality. */
	void onQualityChanged(float quality);

	/** A frame was drawn. May come on another thread. */
	void onFrame();

	/** Mark in the editor what the run reported. */
	void showErrors(@NonNull List<ProjectDiagnostic> infoLog);

	/**
	 * Nothing runs: what a run reported is gone, and what the next one
	 * reports is not known yet.
	 */
	void forgetErrors();

	/**
	 * Return whether diagnostic is about what is open, not of a build
	 * of what was open before, whose report may come after.
	 */
	boolean isAbout(@NonNull ProjectDiagnostic diagnostic);

	/**
	 * Tune the number the caret is on, as the strip offers it; only
	 * where the shaders are files.
	 */
	default void tune() {
	}

	/** Return whether a number is being tuned. */
	default boolean isTuning() {
		return false;
	}

	/** The text in the editor changed. */
	default void onTextModified() {
	}
}
