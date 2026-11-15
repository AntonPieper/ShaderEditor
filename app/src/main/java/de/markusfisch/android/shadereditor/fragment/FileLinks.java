package de.markusfisch.android.shadereditor.fragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.project.HostCompletion;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The files around the file of a folder the editor shows: what the
 * lines naming files complete with. Only project mode has them; the
 * listings come from memory, and a folder not listed yet is listed off
 * the main thread. Call it on the main thread.
 */
public interface FileLinks extends HostCompletion.Listing {
	/** Return the file the editor shows. */
	@NonNull
	ProjectPath file();

	/** Return whether this device draws into rgba16f targets. */
	boolean drawsHalfFloat();

	/**
	 * The caret entered a path: list the folders asked for from now on
	 * again, off the main thread, as files may have been made since.
	 */
	void refresh();

	/** Run changed when a folder asked for is listed, none when null. */
	void setListener(@Nullable Runnable changed);
}
