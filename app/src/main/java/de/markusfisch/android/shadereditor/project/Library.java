package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter;
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

	/** A file of the library's root, by its name. */
	public record RootFile(String name, long modified) {
	}

	/**
	 * Return the library's folder; it may not be there yet. Only this
	 * layer has it: a screen reads the library through the readers
	 * below and changes it through {@link Projects}.
	 */
	File folder() {
		return new File(userFiles, FOLDER);
	}

	/**
	 * Return the files of the library's root as they are in storage,
	 * last changed first: no folders, and no files whose names begin
	 * with a dot. For the lists outside the drawer, which count and
	 * offer the shaders. Off the main thread.
	 */
	public List<RootFile> rootFiles() {
		File[] files = folder().listFiles();
		files = files != null ? files : new File[0];
		Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(),
				a.lastModified()));
		List<RootFile> root = new ArrayList<>();
		for (File file : files) {
			if (file.isFile() && !file.getName().startsWith(".")) {
				root.add(new RootFile(file.getName(), file.lastModified()));
			}
		}
		return root;
	}

	/**
	 * Build the file at path as it is saved, for what shows it outside
	 * the editor. Off the main thread.
	 */
	public Preparation prepareSaved(ProjectPath path, Builtins builtins) {
		return Preparation.prepare(new DirectoryProjectFiles(folder()),
				path, ProjectLimits.DEFAULT, builtins);
	}

	/**
	 * Return the file a start shows of the library opened as project:
	 * shown, the one it showed last, while the folder still lists it or
	 * held says that it has text that is not saved; else the newest
	 * file of the root, and null for a library without files. Off the
	 * main thread.
	 */
	public static ProjectPath startFile(OpenProject project,
			ProjectPath shown, boolean held) {
		return shown != null && (held || exists(project, shown))
				? shown
				: newest(project.list(null));
	}

	// Whether the folder of path lists it; one that cannot be listed
	// is left to the opening to tell.
	private static boolean exists(OpenProject project, ProjectPath path) {
		String text = path.toString();
		int slash = text.lastIndexOf('/');
		var parent = slash > 0
				? ProjectPath.parse(text.substring(0, slash))
				: null;
		OpenProject.Folder folder = project.list(parent != null &&
				parent.isSuccess() ? parent.value() : null);
		if (folder.problem() != null) {
			return true;
		}
		for (OpenProject.Item item : folder.items()) {
			if (!item.folder() && item.path().equals(path)) {
				return true;
			}
		}
		return false;
	}

	static ProjectPath newest(OpenProject.Folder folder) {
		OpenProject.Item newest = null;
		for (OpenProject.Item item : folder.items()) {
			if (!item.folder() && (newest == null ||
					item.modified() > newest.modified())) {
				newest = item;
			}
		}
		return newest != null ? newest.path() : null;
	}

	/** Return what the move of the database shaders has done. */
	public MoveNote note() {
		return new MoveNote(new File(state(), "moved"), this::settled);
	}

	public LibrarySettings settings() {
		return new LibrarySettings(new File(state(), "settings"),
				following(), this::settled);
	}

	public Previews previews() {
		return store(null).previews();
	}

	/**
	 * What is kept by path about the library's files: its pictures and
	 * its settings, which follow its renames together; nothing is
	 * written to it before a redo begun is finished with relocation.
	 */
	PictureStore store(Relocation relocation) {
		return new PictureStore(state(), this::settled,
				() -> enter(relocation), List.of(move -> new LibrarySettings(
				new File(state(), "settings"), null, this::settled)
				.follow(move)));
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

	/**
	 * Return whether the text of the file at path is converted from
	 * ShaderToy's form, as the text of a shader of the database was: a
	 * file of the library's root is a shader of its own. Files in its
	 * folders are not, nor are those of other folders, where a port
	 * from ShaderToy has files that must not be rewritten one by one.
	 */
	public static boolean convertsShaderToy(ProjectPath path) {
		return path.toString().indexOf('/') < 0;
	}

	/** Return the library's files, of a folder that is made if needed. */
	DirectoryProjectFiles files() {
		File folder = folder();
		folder.mkdirs();
		return new DirectoryProjectFiles(folder);
	}

	/**
	 * What an export wrote: how many files, and how many files and
	 * folders with a dot name it left out; or why nothing was written.
	 */
	public record Exported(int files, int hidden, String problem) {
	}

	/**
	 * Write the library to out as one zip, see {@link LibraryArchive}:
	 * its files as they are saved, and what is kept beside them. What
	 * was written to out before a problem is of no use.
	 *
	 * @param app  the version of the app
	 * @param time the time of the export
	 */
	Exported export(String app, long time, OutputStream out,
			Relocation relocation) {
		String barrier = enter(relocation);
		if (barrier != null) {
			return new Exported(0, 0, barrier);
		}
		LibrarySettings.State settings;
		try {
			settings = settings().read();
		} catch (IOException e) {
			return new Exported(0, 0, "the settings of the library cannot " +
					"be read");
		}
		try {
			LibraryExport.Written written = LibraryExport.write(files(),
					settings, app, time, LibraryArchive.Limits.DEFAULT, out);
			return new Exported(written.files(), written.hidden(), null);
		} catch (LibraryArchive.Refused e) {
			return new Exported(0, 0, e.getMessage());
		} catch (IOException e) {
			return new Exported(0, 0, "the file cannot be written" +
					(e.getMessage() != null ? ": " + e.getMessage() : ""));
		}
	}

	/**
	 * What an import did: how many shaders and other files it made, how
	 * many were there already, what came in under another name because
	 * its own was taken, by its path in the archive, the files the
	 * library has with other bytes, which were kept as they are, how
	 * many entries with a dot name were skipped, and whether the
	 * qualities of the new files could not be kept. With a problem
	 * nothing was imported, and the library is as it was.
	 */
	public record Imported(int shaders, int files, int there,
			Map<ProjectPath, ProjectPath> renamed, List<ProjectPath> kept,
			int hidden, boolean unkept, String problem) {
		public Imported {
			renamed = Collections.unmodifiableMap(
					new LinkedHashMap<>(renamed));
			kept = List.copyOf(kept);
		}

		static Imported refused(String problem) {
			return new Imported(0, 0, 0, Map.of(), List.of(), 0, false,
					problem);
		}
	}

	/**
	 * Add the files of the zip in to the library, see
	 * {@link LibraryImport} and {@link LibraryMerge}: it is read whole
	 * into staging, a folder of its own that is removed again, and
	 * only an archive that is good as a whole is added. It may unpack
	 * to half of the room there is, as its files are there twice until
	 * it is done.
	 */
	Imported take(InputStream in, File staging, Relocation relocation) {
		String barrier = enter(relocation);
		if (barrier != null) {
			return Imported.refused(barrier);
		}
		// One left by a process that died.
		remove(staging);
		DirectoryProjectFiles files = files();
		File folder = folder();
		LibraryArchive.Limits most = LibraryArchive.Limits.DEFAULT;
		long room = folder.getUsableSpace() / 2;
		try {
			LibraryImport.Archive archive = LibraryImport.read(in, staging,
					new LibraryArchive.Limits(most.entries(),
							Math.min(most.bytes(), room)));
			LibraryMerge.Result merged = LibraryMerge.place(files, archive,
					settings(), (path, time) -> new File(folder,
							path.toString()).setLastModified(time));
			return new Imported(merged.shaders(), merged.files(),
					merged.there(), merged.renamed(), merged.kept(),
					archive.hidden(), merged.unkept(), merged.problem());
		} catch (LibraryArchive.TooLarge e) {
			return Imported.refused(e.getMessage() + (room < most.bytes()
					? ", and this phone has no room for more"
					: ""));
		} catch (LibraryArchive.Refused e) {
			return Imported.refused(e.getMessage());
		} finally {
			remove(staging);
		}
	}

	private static void remove(File file) {
		File[] inside = file.listFiles();
		for (File child : inside != null ? inside : new File[0]) {
			remove(child);
		}
		file.delete();
	}

	/**
	 * What follows the files of the library when its project renames
	 * or deletes one: the settings and the pictures kept by path.
	 */
	FileTexts.Kept kept() {
		return store(null).kept();
	}

	/**
	 * Let the settings and pictures follow the renames and deletions
	 * that are noted for them, in their order. Return whether none is
	 * left to follow.
	 */
	boolean follow() {
		return store(null).follow();
	}

	private Following following() {
		return new Following(new File(state(), "following"),
				this::settled);
	}

	// Whether no redo is begun: while one is, nothing is written into
	// what the app keeps about the library, by whoever asks.
	private boolean settled() {
		return !new File(appFiles, REDO).exists();
	}

	/**
	 * The one way in for whatever changes the library or what is kept
	 * about it: a redo that was begun is finished first, and what is
	 * kept follows the renames it has not followed yet. Return why
	 * nothing may be changed now, or null.
	 */
	String enter(Relocation relocation) {
		String barrier = finishRedo(relocation);
		if (barrier != null) {
			return barrier;
		}
		return follow() ? null : "what is kept about the files of the " +
				"library has not followed a rename yet; try again";
	}

	/**
	 * Keep quality for the file at path. Return why it is not kept, or
	 * null.
	 */
	String setQuality(ProjectPath path, float quality,
			Relocation relocation) {
		String refused = enter(relocation);
		return refused != null ? refused
				: settings().setQuality(path, quality) ? null
				: "the settings of the library cannot be written";
	}

	/**
	 * Keep png as the picture of the file at path, made from its
	 * content at revision. Return why it is not kept, or null.
	 */
	String setPreview(ProjectPath path, String revision, byte[] png,
			Relocation relocation) {
		return store(relocation).put(path, revision, png);
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
		String barrier = enter(relocation);
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
		String barrier = enter(relocation);
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
		String barrier = enter(relocation);
		if (barrier != null) {
			return new Created(null, barrier);
		}
		ProjectFiles files = files();
		Template template;
		try {
			template = template(files);
		} catch (IOException e) {
			return new Created(null, e.getMessage());
		}
		return write(files, Naming.stem(title, untitled),
				template != null ? template.bytes : builtIn,
				path -> dress(path, template, builtIn, picture));
	}

	/**
	 * Make a new shader in the library's root from the text of a shader
	 * as the database held it, such as an example or text another app
	 * shared, converted as the move converts: the textures it reads
	 * are written to textures/, where one with the same bytes is used
	 * and another is never written over, and the file gets quality and
	 * the converted shader's picture. The file is named as by
	 * {@link #create}.
	 */
	Created add(String title, String untitled, LibraryMove.Converted converted,
			float quality, Relocation relocation) {
		String barrier = enter(relocation);
		if (barrier != null) {
			return new Created(null, barrier);
		} else if (converted.text() == null) {
			return new Created(null, converted.loss() != null
					? converted.loss()
					: "it has no text");
		}
		byte[] bytes = converted.text().getBytes(StandardCharsets.UTF_8);
		if (bytes.length > Document.MAX_BYTES) {
			return new Created(null, "it is larger than " +
					(Document.MAX_BYTES >> 20) + " MiB, the most text a " +
					"file may hold");
		}
		ProjectFiles files = files();
		String problem = dependencies(files, converted);
		if (problem != null) {
			return new Created(null, problem);
		}
		byte[] picture = converted.thumbnail();
		return write(files, Naming.stem(title, untitled), bytes, path -> {
			boolean kept = settings().setQuality(path, quality);
			boolean drawn = picture == null || previews().put(path,
					StoredFiles.revision(bytes), picture);
			return kept && drawn
					? null
					: "the quality or the picture of " + path +
							" could not be kept";
		});
	}

	/**
	 * Make a folder in the library's root that holds texts, by their
	 * paths in it, in their order: a shader of several files that was
	 * made elsewhere, such as one imported from ShaderToy. The folder
	 * is title made a name, or untitled when title is null, with a
	 * number when that is taken, so nothing is written over. A folder
	 * that cannot be written whole is removed again. Return the path
	 * of entry, one of texts, in it.
	 */
	Created addFolder(String title, String untitled,
			Map<String, String> texts, String entry, Relocation relocation) {
		String barrier = enter(relocation);
		if (barrier != null) {
			return new Created(null, barrier);
		}
		ProjectFiles files = files();
		if (!(files.list(null) instanceof ProjectFiles.Entries entries)) {
			return new Created(null, "the library cannot be listed");
		}
		List<String> names = new ArrayList<>();
		for (ProjectFiles.Entry listed : entries.entries()) {
			names.add(listed.name());
		}
		String folder = new Naming(names).unused(
				Naming.stem(title, untitled), "");
		Map<ProjectPath, ProjectPath> places = new LinkedHashMap<>();
		Map<ProjectPath, String> placed = new LinkedHashMap<>();
		for (Map.Entry<String, String> text : texts.entrySet()) {
			String path = folder + "/" + text.getKey();
			ProjectParseResult<ProjectPath> place = ProjectPath.parse(path);
			if (!place.isSuccess() || !place.value().toString().equals(path)) {
				return new Created(null, path + " is not a path of a file");
			}
			places.put(place.value(), place.value());
			placed.put(place.value(), text.getValue());
		}
		Copy copy = copy(places);
		for (Map.Entry<ProjectPath, String> text : placed.entrySet()) {
			if (copy.write(text.getKey(), text.getValue().getBytes(
					StandardCharsets.UTF_8), null)
					instanceof ProjectFiles.Refused refused) {
				String left = copy.undo();
				return new Created(null, text.getKey() +
						" cannot be written: " + refused.reason() +
						(left != null ? "; " + left : ""));
			}
		}
		return new Created(ProjectPath.parse(folder + "/" + entry).value(),
				null);
	}

	/**
	 * Return where Save a copy puts files of a read-only folder in the
	 * library, by their path there, as they lie in that folder: entry
	 * in its root becomes a file of the library's root named as a new
	 * shader of its name is ("Tilt 2.frag" when "Tilt.frag" is taken);
	 * entry in a folder takes that whole folder along under an unused
	 * name ("Ink 2/main.frag"). The other files keep their paths, so
	 * that a shared lib/ or textures/ is reached as before; one that
	 * is in the library already is used as it is and left out here,
	 * never written over. Return null when the root cannot be listed.
	 */
	Map<ProjectPath, ProjectPath> places(ProjectPath entry,
			Collection<ProjectPath> paths) {
		ProjectFiles files = files();
		ProjectFiles.Listing listing = files.list(null);
		if (!(listing instanceof ProjectFiles.Entries entries)) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for (ProjectFiles.Entry listed : entries.entries()) {
			names.add(listed.name());
		}
		Naming naming = new Naming(names);
		String path = entry.toString();
		int slash = path.indexOf('/');
		String folder = slash > 0 ? path.substring(0, slash + 1) : null;
		String renamed = folder != null
				? naming.unused(folder.substring(0, slash), "") + "/"
				: null;
		Map<ProjectPath, ProjectPath> places = new LinkedHashMap<>();
		for (ProjectPath copied : paths) {
			String at = copied.toString();
			ProjectParseResult<ProjectPath> place;
			if (copied.equals(entry) && folder == null) {
				place = ProjectPath.parse(naming.unused(Naming.stem(
						entry.getFileName(), "Untitled"), ShaderLanguage.PASS));
			} else if (folder != null && at.startsWith(folder)) {
				place = ProjectPath.parse(renamed + at.substring(folder.length()));
			} else if (files.head(copied, 1) != null ||
					files.list(copied) != null) {
				continue;
			} else {
				place = ProjectParseResult.success(copied);
			}
			if (place.isSuccess()) {
				places.put(copied, place.value());
			}
		}
		return places;
	}

	/**
	 * What Save a copy made: the path of the file to show, or null when
	 * nothing was copied, the paths of the read-only folder that were
	 * copied, those the library has already with other bytes, which
	 * were kept as they are and which the copy now reads, and why the
	 * copy failed, or null.
	 */
	public record Copied(ProjectPath path, List<ProjectPath> copied,
			List<ProjectPath> kept, String problem) {
		public Copied {
			copied = List.copyOf(copied);
			kept = List.copyOf(kept);
		}
	}

	/**
	 * Return the store Save a copy writes into, for places from
	 * {@link #places}: a file with a place is written there, never over
	 * another, and noted as copied; any other file is one the library
	 * has, which stays as it is and is noted as kept when the copy's
	 * bytes differ. Write from one thread at a time.
	 */
	Copy copy(Map<ProjectPath, ProjectPath> places) {
		return new Copy(files(), places);
	}

	/** A copy into the library, as {@link #copy} makes it. */
	static final class Copy implements ProjectFiles {
		private final ProjectFiles files;
		private final Map<ProjectPath, ProjectPath> places;
		private final List<ProjectPath> copied =
				Collections.synchronizedList(new ArrayList<>());
		private final List<ProjectPath> kept =
				Collections.synchronizedList(new ArrayList<>());

		private Copy(ProjectFiles files, Map<ProjectPath, ProjectPath> places) {
			this.files = files;
			this.places = places;
		}

		@Override
		public Read read(ProjectPath path) {
			return null;
		}

		@Override
		public Change write(ProjectPath path, byte[] bytes,
				String expectedRevision) {
			ProjectPath place = places.get(path);
			if (place != null) {
				Change change = files.write(place, bytes, null);
				if (change instanceof Done) {
					copied.add(path);
				}
				return change;
			}
			if (!(files.read(path) instanceof Content content &&
					Arrays.equals(content.bytes(), bytes))) {
				kept.add(path);
			}
			return new Done(null);
		}

		List<ProjectPath> copied() {
			return new ArrayList<>(copied);
		}

		List<ProjectPath> kept() {
			return new ArrayList<>(kept);
		}

		/**
		 * Remove what this copy wrote, so that a copy that failed half
		 * way leaves nothing: every file it wrote, and the folder it
		 * made for a shader of a folder. Return why something stayed,
		 * or null.
		 */
		String undo() {
			List<String> left = new ArrayList<>();
			for (ProjectPath path : copied()) {
				ProjectPath place = places.get(path);
				if (files.delete(place) instanceof Refused) {
					left.add(place.toString());
				}
			}
			// A folder the copy made under an unused name is its own.
			for (ProjectPath place : places.values()) {
				String at = place.toString();
				int slash = at.indexOf('/');
				ProjectParseResult<ProjectPath> folder = slash > 0
						? ProjectPath.parse(at.substring(0, slash))
						: null;
				if (folder != null && folder.isSuccess() &&
						!places.containsValue(folder.value()) &&
						isEmpty(folder.value())) {
					files.delete(folder.value());
				}
			}
			copied.clear();
			StringBuilder sb = new StringBuilder();
			for (String path : left) {
				sb.append(sb.length() > 0 ? ", " : "").append(path);
			}
			return left.isEmpty()
					? null
					: sb + " could not be removed";
		}

		private boolean isEmpty(ProjectPath folder) {
			ProjectFiles.Listing listing = files.list(folder);
			return listing instanceof ProjectFiles.Entries entries &&
					entries.entries().isEmpty();
		}
	}

	// What is kept of a file just written; why something is not, or null.
	private interface Dress {
		String of(ProjectPath path);
	}

	// Write bytes to an unused name of stem in the root; a name another
	// writer took meanwhile is passed over.
	private static Created write(ProjectFiles files, String stem,
			byte[] bytes, Dress dress) {
		ProjectFiles.Listing listing = files.list(null);
		if (!(listing instanceof ProjectFiles.Entries entries)) {
			return new Created(null, "the library cannot be listed" +
					(listing instanceof ProjectFiles.Failed failed
							? ": " + failed.reason()
							: ""));
		}
		List<String> names = new ArrayList<>();
		for (ProjectFiles.Entry entry : entries.entries()) {
			names.add(entry.name());
		}
		Naming naming = new Naming(names);
		for (int tries = 0; tries < 16; ++tries) {
			String name = naming.unused(stem, ShaderLanguage.PASS);
			ProjectParseResult<ProjectPath> path = ProjectPath.parse(name);
			if (!path.isSuccess()) {
				return new Created(null, name + " is not a file name");
			}
			ProjectFiles.Change change = files.write(path.value(), bytes,
					null);
			if (change instanceof ProjectFiles.Done) {
				return new Created(path.value(), dress.of(path.value()));
			} else if (!exists((ProjectFiles.Refused) change)) {
				return new Created(null, name + " cannot be written: " +
						((ProjectFiles.Refused) change).reason());
			}
			naming.take(name);
		}
		return new Created(null, "no unused name was found for " + stem);
	}

	// The textures and generated files the converted shader reads.
	private static String dependencies(ProjectFiles files,
			LibraryMove.Converted converted) {
		LegacyShaderConverter.Result conversion = converted.conversion();
		for (Map.Entry<ProjectPath, String> file :
				conversion.files().entrySet()) {
			if (!file.getKey().equals(LegacyShaderConverter.SHADER)) {
				String problem = dependency(files, file.getKey(),
						file.getValue().getBytes(StandardCharsets.UTF_8));
				if (problem != null) {
					return problem;
				}
			}
		}
		for (LegacyShaderConverter.TextureFile texture :
				conversion.textures()) {
			ProjectFiles.Read read = converted.files().read(texture.path());
			if (!(read instanceof ProjectFiles.Content content)) {
				return read instanceof ProjectFiles.Failed failed
						? failed.reason()
						: "the texture \"" + texture.name() + "\" is gone";
			}
			String problem = dependency(files, texture.path(),
					content.bytes());
			if (problem != null) {
				return problem;
			}
		}
		return null;
	}

	// One that is there with these bytes is used; another stays.
	private static String dependency(ProjectFiles files, ProjectPath path,
			byte[] bytes) {
		ProjectFiles.Change change = files.write(path, bytes, null);
		if (!(change instanceof ProjectFiles.Refused refused)) {
			return null;
		} else if (!exists(refused)) {
			return path + " cannot be written: " + refused.reason();
		}
		return files.read(path) instanceof ProjectFiles.Content content &&
				Arrays.equals(content.bytes(), bytes)
				? null
				: path + " is another file than the one this shader " +
						"reads; rename it and try again";
	}

	private static boolean exists(ProjectFiles.Refused refused) {
		return refused.refusal() == ProjectFiles.Refusal.EXISTS ||
				refused.refusal() == ProjectFiles.Refusal.CONFLICT;
	}

	/**
	 * Give the shader at path, which {@link #create} made, its quality
	 * and its picture again, after they could not be kept. No file is
	 * made. Return what still could not be kept, or null.
	 */
	String dress(ProjectPath path, byte[] builtIn, byte[] picture,
			Relocation relocation) {
		String barrier = enter(relocation);
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
		String problem = aside(folder(), folderAside, FOLDER + ASIDE);
		if (problem == null) {
			problem = aside(state(), new File(appFiles, names[1]),
					STATE + ASIDE);
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
	// was nothing to rename. With both there, the target is what was
	// put aside, because its name was free when the redo was noted,
	// and the source was made since: it is put aside too, under the
	// next name that is free, so that the redo always gets on.
	private static String aside(File from, File to, String stem) {
		File target = from.exists() && to.exists()
				? new File(to.getParentFile(),
						unused(to.getParentFile(), stem))
				: to;
		return !from.exists() || from.renameTo(target)
				? null
				: from.getName() + " cannot be renamed to " +
						target.getName();
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
