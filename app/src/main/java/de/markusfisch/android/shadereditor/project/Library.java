package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The library: the folder of the app's own user files that holds the
 * user's shaders, one file each in its root, and what the app keeps
 * about them beside the user files. An ordinary project as far as its
 * files go; this is only where it is and what belongs to it.
 * <p>
 * Anyone may read from it, off the main thread. Whatever changes its
 * names or what is kept about it is done by {@link Projects}, on the
 * one queue the projects work on, and by nobody else: the app is one
 * process, and that queue is the one owner.
 */
public final class Library {
	/** The name of the library's folder in the user files. */
	public static final String FOLDER = "Shaders";

	/**
	 * Follows the library when it is put aside: what was kept by the
	 * place of its folder is kept for the new place.
	 */
	interface Relocation {
		/** Return whether nothing is left that names from. */
		boolean moved(File from, File to);
	}

	private static final String STATE = "library";
	private static final String REDO = "library-redo";
	private static final String ASIDE = " before redo";

	private final File userFiles;
	private final File appFiles;

	/**
	 * @param userFiles the folder of the user's own files, which other
	 *                  apps see
	 * @param appFiles  the app's files folder, which they do not
	 */
	public Library(File userFiles, File appFiles) {
		this.userFiles = userFiles;
		this.appFiles = appFiles;
	}

	/** Return the library's folder; it may not be there yet. */
	public File folder() {
		return new File(userFiles, FOLDER);
	}

	/** Return what the move of the database shaders has done. */
	public MoveNote note() {
		return new MoveNote(new File(state(), "moved"));
	}

	public LibrarySettings settings() {
		return new LibrarySettings(new File(state(), "settings"));
	}

	public Previews previews() {
		return new Previews(new File(state(), "previews"));
	}

	/** Return the library's files, of a folder that is made if needed. */
	ProjectFiles files() {
		File folder = folder();
		folder.mkdirs();
		return new DirectoryProjectFiles(folder);
	}

	/**
	 * What follows the files of the library when its project renames
	 * or deletes one: the settings and the pictures kept by path.
	 */
	FileTexts.Kept kept() {
		return move -> {
			boolean settings = settings().follow(move);
			return previews().follow(move) && settings;
		};
	}

	/**
	 * Move every shader of source that is not moved yet into the
	 * library, see {@link LibraryMove}. A redo that did not finish is
	 * finished first, and while it cannot be, nothing is moved.
	 *
	 * @param untitled the stem of the name of a shader without a title
	 */
	LibraryMove.Result move(LibraryMove.Source source, String untitled,
			LibraryMove.Chosen chosen, LibraryMove.Selector selector,
			Relocation relocation) {
		String barrier = finishRedo(relocation);
		if (barrier != null) {
			return LibraryMove.Result.blocked(barrier);
		}
		File folder = folder();
		return new LibraryMove(source, files(), note(), settings(),
				previews(), (path, time) -> new File(folder,
						path.toString()).setLastModified(time),
				selector, untitled, chosen).run();
	}

	/**
	 * Put the library and what the app keeps about it aside under
	 * other names, so that a move starts with none. Nothing is deleted.
	 * Return why it could not be done, or null when it was, or when
	 * there was nothing to put aside.
	 */
	String putAside(Relocation relocation) {
		// One that was begun is finished first, and never replaced.
		String barrier = finishRedo(relocation);
		if (barrier != null) {
			return barrier;
		}
		File state = state();
		if (!folder().exists() && !state.exists()) {
			return null;
		}
		String folderAside = unused(userFiles, FOLDER + ASIDE);
		String stateAside = unused(appFiles, STATE + ASIDE);
		// Noted first: until both renames are made and the note is
		// gone, nothing else touches the library.
		if (!StateFile.replace(new File(appFiles, REDO),
				(folderAside + "\n" + stateAside + "\n").getBytes(
						StandardCharsets.UTF_8))) {
			return "the redo cannot be noted";
		}
		return finishRedo(relocation);
	}

	/**
	 * Finish a redo that was begun, when there is one. Return null when
	 * none is unfinished now, else why it is: then nothing of the
	 * library may be touched.
	 */
	String finishRedo(Relocation relocation) {
		File redo = new File(appFiles, REDO);
		byte[] bytes;
		try {
			bytes = StateFile.read(redo);
		} catch (IOException e) {
			return "a redo of the move was begun, and its note cannot " +
					"be read";
		}
		if (bytes == null) {
			return null;
		}
		String[] names = new String(bytes, StandardCharsets.UTF_8)
				.split("\n", -1);
		if (names.length != 3 || names[0].isEmpty() || names[1].isEmpty() ||
				!names[2].isEmpty()) {
			return "a redo of the move was begun, and its note says " +
					"nothing";
		}
		File folderAside = new File(userFiles, names[0]);
		String problem = aside(folder(), folderAside);
		if (problem == null) {
			problem = aside(state(), new File(appFiles, names[1]));
		}
		if (problem == null && relocation != null &&
				!relocation.moved(folder(), folderAside)) {
			problem = "what was open of the library cannot follow it";
		}
		if (problem == null && !redo.delete()) {
			problem = "the note of the redo cannot be removed";
		}
		return problem != null
				? "a redo of the move is not finished: " + problem
				: null;
	}

	// One of the two renames, told by what is there: only the source,
	// it is made now; only the target, it was made; neither, there
	// was nothing to rename.
	private static String aside(File from, File to) {
		if (from.exists() && to.exists()) {
			return "both " + from.getName() + " and " + to.getName() +
					" are there";
		}
		return !from.exists() || from.renameTo(to)
				? null
				: from.getName() + " cannot be renamed to " + to.getName();
	}

	private static String unused(File folder, String stem) {
		List<String> names = new ArrayList<>();
		String[] listed = folder.list();
		if (listed != null) {
			names.addAll(List.of(listed));
		}
		return new Naming(names).unused(stem, "");
	}

	private File state() {
		return new File(appFiles, STATE);
	}
}
