package de.markusfisch.android.shadereditor.fragment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.HostCompletion;
import de.markusfisch.android.shadereditor.project.OpenProject;
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
	 * Make the text file at path, a pass from the template for a new
	 * shader, or an empty include with an include guard; then build
	 * what runs again.
	 */
	void create(@NonNull ProjectPath path, boolean include);

	/**
	 * Return the path a file at path was renamed to while the project
	 * is open, or null when it was not.
	 */
	@Nullable
	ProjectPath renamed(@NonNull ProjectPath path);

	/**
	 * The caret entered a path: list the folders asked for from now on
	 * again, off the main thread, as files may have been made since.
	 */
	void refresh();

	/**
	 * Open path at line, from 1, or where its caret is when 0, from the
	 * line the caret is on: the one way a file is opened from a line;
	 * where the caret was goes on the way back.
	 */
	default void open(@NonNull ProjectPath path, int line) {
		open(path, line, null);
	}

	/**
	 * Open path at line as above; the key back is "Uses" and runs then
	 * after leading back, when then is not null.
	 */
	void open(@NonNull ProjectPath path, int line, @Nullable Runnable then);

	/**
	 * Go back one place on the way back, as its key does; return
	 * whether there was one.
	 */
	default boolean back() {
		return false;
	}

	/** Run jumping before going back moves the caret, none for null. */
	default void setJumping(@Nullable Runnable jumping) {
	}

	/** What going back is called, as its key says it, or null. */
	@Nullable
	default String wayBack() {
		return null;
	}

	/**
	 * Tell done the texts of the folder's GLSL files as the editor has
	 * them, read off the main thread: of at most most files and bytes
	 * bytes, see {@link OpenProject#folderTexts(int, long, FileTexts.Callback)}.
	 */
	void folderTexts(int most, long bytes,
			@NonNull FileTexts.Callback<OpenProject.FolderTexts> done);

	/**
	 * Bring the documents of paths into memory; done gets null once all
	 * are, or why one cannot be opened.
	 */
	void load(@NonNull List<ProjectPath> paths,
			@NonNull FileTexts.Callback<String> done);

	/** Return the document of path while it is in memory, else null. */
	@Nullable
	Document document(@NonNull ProjectPath path);

	/**
	 * Return whether what runs was last built from the texts as they
	 * are now, without an error.
	 */
	boolean builtWithoutErrors();

	/**
	 * Tell done, once what runs is built from the texts as they are now,
	 * whether without an error; nothing when they change before.
	 */
	void afterBuild(@NonNull FileTexts.Callback<Boolean> done);

	/** Run changed when a folder asked for is listed, none when null. */
	void setListener(@Nullable Runnable changed);
}
