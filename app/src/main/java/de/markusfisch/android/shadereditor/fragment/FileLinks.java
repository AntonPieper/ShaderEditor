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
	 * The caret rests in a path that names a file, or in none when
	 * named is null: the strip shows that file in place of completions.
	 */
	void point(@Nullable HostCompletion.Named named);

	/**
	 * The caret entered a path: list the folders asked for from now on
	 * again, off the main thread, as files may have been made since.
	 */
	void refresh();

	/**
	 * Open path at line, from 1, or where its caret is when 0, from the
	 * line the caret is on: the one way a file is opened from a line,
	 * after which a key leads back to that line once.
	 */
	void open(@NonNull ProjectPath path, int line);

	/** Run changed when a folder asked for is listed, none when null. */
	void setListener(@Nullable Runnable changed);
}
