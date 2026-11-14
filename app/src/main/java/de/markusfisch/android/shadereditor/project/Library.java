package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

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
	 * A new shader of the library. With a path the file is there, and
	 * a problem then says what of its quality and picture could not be
	 * kept: {@link #dress} tries that again for the same file. Without
	 * a path no file was made, and problem says why.
	 */
	public record Created(ProjectPath path, String problem) {
	}

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

	/**
	 * Return the file of the library the live wallpaper shows, for a
	 * list to mark, or null when it shows none of them: the file the
	 * installed snapshot was taken of, or, while the wallpaper still
	 * shows the shader of the database with this id, the file that
	 * shader was moved to. The file may have changed since, or be gone.
	 */
	public ProjectPath wallpaperFile(WallpaperSnapshots.Snapshot installed,
			long databaseId) {
		return installed != null
				? installed.origin()
				: note().moved(databaseId);
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
	 * Keep path, a file in the library's root, as the shader new ones
	 * are copied from, none when null. A file in a folder is refused:
	 * a copy of it in the root would look for its includes and
	 * textures in the wrong place. Return why it is not kept, or null.
	 */
	String setTemplate(ProjectPath path, Relocation relocation) {
		String barrier = finishRedo(relocation);
		if (barrier != null) {
			return barrier;
		} else if (path != null && path.toString().indexOf('/') > -1) {
			return "the shader new ones are copied from must be in " +
					"the library itself, not in a folder of it";
		}
		return settings().setTemplate(path)
				? null
				: "the settings of the library cannot be written";
	}

	/**
	 * Make a new shader in the library's root, a copy of the shader
	 * new ones are copied from with its quality and picture, or of the
	 * built-in template when none is chosen, or the chosen file is
	 * gone or is not text. The file is title made a file name, or
	 * untitled when title is null, with a number when that is taken;
	 * no file is written over.
	 *
	 * @param builtIn the text of the built-in template
	 * @param picture the picture of the built-in template, or null
	 */
	Created create(String title, String untitled, byte[] builtIn,
			byte[] picture, Relocation relocation) {
		String barrier = finishRedo(relocation);
		if (barrier != null) {
			return new Created(null, barrier);
		}
		ProjectFiles files = files();
		ProjectFiles.Listing listing = files.list(null);
		if (!(listing instanceof ProjectFiles.Entries entries)) {
			return new Created(null, "the library cannot be listed" +
					(listing instanceof ProjectFiles.Failed failed
							? ": " + failed.reason()
							: ""));
		}
		Template template;
		try {
			template = template(files);
		} catch (IOException e) {
			return new Created(null, e.getMessage());
		}
		List<String> names = new ArrayList<>();
		for (ProjectFiles.Entry entry : entries.entries()) {
			names.add(entry.name());
		}
		Naming naming = new Naming(names);
		byte[] bytes = template != null ? template.bytes : builtIn;
		String stem = Naming.stem(title, untitled);
		// A name another writer took meanwhile is passed over.
		for (int tries = 0; tries < 16; ++tries) {
			String name = naming.unused(stem, ShaderLanguage.PASS);
			ProjectParseResult<ProjectPath> path = ProjectPath.parse(name);
			if (!path.isSuccess()) {
				return new Created(null, name + " is not a file name");
			}
			ProjectFiles.Change change = files.write(path.value(), bytes,
					null);
			if (change instanceof ProjectFiles.Done) {
				return new Created(path.value(), dress(path.value(),
						template, builtIn, picture));
			}
			ProjectFiles.Refused refused = (ProjectFiles.Refused) change;
			if (refused.refusal() != ProjectFiles.Refusal.EXISTS &&
					refused.refusal() != ProjectFiles.Refusal.CONFLICT) {
				return new Created(null, name + " cannot be written: " +
						refused.reason());
			}
			naming.take(name);
		}
		return new Created(null, "no unused name was found for " + stem);
	}

	/**
	 * Give the shader at path, which {@link #create} made, its quality
	 * and its picture again, after they could not be kept. No file is
	 * made. Return what still could not be kept, or null.
	 */
	String dress(ProjectPath path, byte[] builtIn, byte[] picture,
			Relocation relocation) {
		String barrier = finishRedo(relocation);
		if (barrier != null) {
			return barrier;
		}
		try {
			return dress(path, template(files()), builtIn, picture);
		} catch (IOException e) {
			return e.getMessage();
		}
	}

	// The file new shaders are copied from, with what is kept of it.
	private record Template(byte[] bytes, float quality,
			Previews.Picture picture) {
	}

	// Return the template, or null for the built-in one: none is
	// chosen, or the chosen file is gone or is not text.
	private Template template(ProjectFiles files) throws IOException {
		LibrarySettings.State settings;
		try {
			settings = settings().read();
		} catch (IOException e) {
			throw new IOException("the settings of the library cannot " +
					"be read");
		}
		ProjectPath path = settings.template();
		ProjectFiles.Read read = path != null
				? files.read(path, Document.MAX_BYTES)
				: null;
		if (read instanceof ProjectFiles.Failed failed) {
			throw new IOException(path + ", which new shaders are " +
					"copied from, cannot be read: " + failed.reason());
		}
		return read instanceof ProjectFiles.Content content &&
				FileKind.of(content.bytes()) == FileKind.TEXT
				? new Template(content.bytes(), settings.quality(path),
						previews().get(path))
				: null;
	}

	// A copied picture stays a picture of what it was made from; the
	// built-in one is of the built-in text.
	private String dress(ProjectPath path, Template template,
			byte[] builtIn, byte[] picture) {
		Previews.Picture shown = template != null
				? template.picture
				: picture != null
						? new Previews.Picture(picture,
								StoredFiles.revision(builtIn))
						: null;
		boolean kept = settings().setQuality(path,
				template != null ? template.quality : 1f);
		boolean drawn = shown == null || previews().put(path,
				shown.revision(), shown.png());
		return kept && drawn ? null : "the " + (kept ? "picture" : drawn
				? "quality"
				: "quality and the picture") + " of " + path +
				" could not be kept";
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
