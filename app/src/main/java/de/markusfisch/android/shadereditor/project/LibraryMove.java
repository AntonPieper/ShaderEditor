package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Content;
import de.markusfisch.android.shadereditor.project.ProjectFiles.Refused;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.TextureFile;
import de.markusfisch.android.shadereditor.project.legacy.StoredFiles;

/**
 * Moves the shaders of the database into the library: each becomes a
 * file in the library's root that holds the text the user wrote, with
 * the textures it reads beside it, its picture and its settings. The
 * database is only read. A file is created and never written over, a
 * shader is moved once, and the note says how far a run came, so that
 * running it again after it died, or at any later time, makes no
 * second copy and loses nothing. Run it on the queue the projects
 * work on; nothing it meets is thrown.
 */
public final class LibraryMove {
	/** The database as the move reads it. Nothing here writes. */
	public interface Source {
		/**
		 * Return the shaders, in the order of their ids, without their
		 * text and pictures.
		 */
		List<Shader> shaders();

		/**
		 * Return the shader id converted to project files, or null
		 * when there is no such shader anymore.
		 */
		Converted convert(long id);
	}

	/**
	 * A shader of the database: its title, null or empty when it has
	 * none, its quality, when it was last modified in milliseconds
	 * since the epoch, 0 when that is not known, and how many bytes
	 * its text has, -1 when that is not known.
	 */
	public record Shader(long id, String title, float quality,
			long modified, long size) {
	}

	/**
	 * A converted shader.
	 *
	 * @param conversion the conversion, for the files it generated and
	 *                   the textures it reads
	 * @param text       the text of the shader's file: the converted
	 *                   shader with everything the user wrote, or null
	 *                   when there is no such text
	 * @param loss       why there is none
	 * @param files      the files the conversion names, with the images
	 *                   of its textures
	 * @param thumbnail  its picture as a PNG, or null
	 */
	public record Converted(LegacyShaderConverter.Result conversion,
			String text, String loss, ProjectFiles files,
			byte[] thumbnail) {
	}

	/**
	 * The ids the preferences hold of shaders of the database: the one
	 * new shaders are copied from and the one opened last, 0 for none.
	 */
	public record Chosen(long template, long lastOpened) {
	}

	/** Sets when a file of the library was last modified. */
	public interface Stamp {
		void modified(ProjectPath path, long time);
	}

	/** Makes a file what the library shows and runs when it opens. */
	public interface Selector {
		/** Return whether path is that now. */
		boolean select(ProjectPath path);
	}

	/**
	 * A shader that this run moved to path; changes are what the
	 * conversion could not keep exactly, at lines of the old text.
	 */
	public record Moved(long id, String title, ProjectPath path,
			List<ProjectDiagnostic> changes) {
		public Moved {
			changes = List.copyOf(changes);
		}
	}

	/**
	 * A shader an earlier run moved, to path as it was named then; the
	 * file may have been renamed or deleted since.
	 */
	public record Skipped(long id, ProjectPath path) {
	}

	/** A shader that is still only in the database, and why. */
	public record Failed(long id, String title, String reason) {
	}

	/**
	 * What a run did with every shader of the database, or, when it
	 * could not run at all, why: then problem is not null and nothing
	 * was looked at.
	 */
	public record Result(List<Moved> moved, List<Skipped> skipped,
			List<Failed> failed, String problem) {
		public Result {
			moved = List.copyOf(moved);
			skipped = List.copyOf(skipped);
			failed = List.copyOf(failed);
		}

		/** A run that could not begin. */
		public static Result blocked(String problem) {
			return new Result(List.of(), List.of(), List.of(), problem);
		}
	}

	private static final String NOTE_FAILED = "the note of the move " +
			"cannot be written";
	private static final String TOO_LARGE = "it is larger than " +
			(Document.MAX_BYTES >> 20) + " MiB, the most text a file " +
			"may hold";

	private final Source source;
	private final ProjectFiles files;
	private final MoveNote note;
	private final LibrarySettings settings;
	private final Previews previews;
	private final Stamp stamp;
	private final Selector selector;
	private final String untitled;
	private final Chosen chosen;
	// What this run found at a path of a texture or generated shader.
	private final Map<ProjectPath, String> written = new HashMap<>();
	// Runs between two steps; a test dies there.
	Runnable between = () -> {
	};

	/**
	 * @param files    the library's store
	 * @param stamp    sets a file's time, or null
	 * @param selector makes a file what the library shows, or null
	 * @param untitled the stem of the name of a shader without a title
	 */
	LibraryMove(Source source, ProjectFiles files, MoveNote note,
			LibrarySettings settings, Previews previews, Stamp stamp,
			Selector selector, String untitled, Chosen chosen) {
		this.source = source;
		this.files = files;
		this.note = note;
		this.settings = settings;
		this.previews = previews;
		this.stamp = stamp;
		this.selector = selector;
		this.untitled = untitled;
		this.chosen = chosen;
	}

	Result run() {
		// Read now, by the one who moves: what the note said when
		// this was made may be older than what another run wrote.
		if (!note.load()) {
			return Result.blocked("the note of what was moved before " +
					"cannot be read, so nothing is moved");
		}
		List<Shader> shaders;
		try {
			shaders = source.shaders();
		} catch (RuntimeException | OutOfMemoryError e) {
			return Result.blocked("the database cannot be read: " +
					reason(e));
		}
		List<Moved> moved = new ArrayList<>();
		List<Skipped> skipped = new ArrayList<>();
		List<Failed> failed = new ArrayList<>();
		Naming names = null;
		String stopped = null;
		for (Shader shader : shaders) {
			ProjectPath before = note.moved(shader.id());
			if (before != null) {
				skipped.add(new Skipped(shader.id(), before));
				continue;
			}
			if (names == null && stopped == null) {
				ProjectFiles.Listing listing = files.list(null);
				if (listing instanceof ProjectFiles.Failed problem) {
					stopped = "the library cannot be listed: " +
							problem.reason();
				} else {
					names = names(listing);
				}
			}
			if (stopped != null) {
				failed.add(new Failed(shader.id(), shader.title(), stopped));
				continue;
			}
			Moved done = null;
			String problem;
			try {
				done = move(shader, names);
				problem = done == null ? NOTE_FAILED : null;
			} catch (Stop stop) {
				problem = stop.getMessage();
			} catch (RuntimeException | OutOfMemoryError e) {
				// What reading the database or converting threw is
				// this shader's failure and nobody else's.
				problem = "it cannot be read: " + reason(e);
			}
			if (done != null) {
				moved.add(done);
			} else {
				failed.add(new Failed(shader.id(), shader.title(), problem));
				// Without the note a second run could not tell what
				// this one did.
				stopped = NOTE_FAILED.equals(problem) ? problem : null;
			}
		}
		if (stopped == null) {
			select();
		}
		return new Result(moved, skipped, failed, null);
	}

	// Why a step failed; the shader is left for the next run.
	private static final class Stop extends RuntimeException {
		private Stop(String reason) {
			super(reason);
		}
	}

	private static String reason(Throwable e) {
		return e instanceof OutOfMemoryError
				? "it does not fit into memory"
				: e.getMessage() != null
						? e.getMessage()
						: e.getClass().getSimpleName();
	}

	// The names of the root and the names noted, also of files that
	// are gone: a name is given once.
	private Naming names(ProjectFiles.Listing listing) {
		List<String> taken = new ArrayList<>(note.names());
		if (listing instanceof ProjectFiles.Entries entries) {
			for (ProjectFiles.Entry entry : entries.entries()) {
				taken.add(entry.name());
			}
		}
		return new Naming(taken);
	}

	// Move one shader; null when the note cannot be written.
	private Moved move(Shader shader, Naming names) {
		if (shader.size() > Document.MAX_BYTES) {
			throw new Stop(TOO_LARGE);
		}
		Converted converted = source.convert(shader.id());
		if (converted == null) {
			throw new Stop("it is gone from the database");
		} else if (converted.text() == null) {
			throw new Stop(converted.loss() != null
					? converted.loss()
					: "it has no text");
		}
		byte[] bytes = converted.text().getBytes(StandardCharsets.UTF_8);
		if (bytes.length > Document.MAX_BYTES) {
			throw new Stop(TOO_LARGE);
		}
		// Named first, so that a shader that fails keeps its place
		// among the names.
		String name = name(shader, names);
		if (name == null) {
			return null;
		}
		String problem = dependencies(converted);
		if (problem != null) {
			throw new Stop(problem);
		}
		ProjectPath path = shader(shader, converted, bytes, name, names);
		return path != null
				? new Moved(shader.id(), shader.title(), path,
						converted.conversion().diagnostics())
				: null;
	}

	// The textures and generated shaders, each once. Return why one
	// could not be written, or null.
	private String dependencies(Converted converted) {
		LegacyShaderConverter.Result conversion = converted.conversion();
		for (Map.Entry<ProjectPath, String> file :
				conversion.files().entrySet()) {
			ProjectPath path = file.getKey();
			if (path.equals(LegacyShaderConverter.SHADER) ||
					file.getValue().equals(written.get(path))) {
				continue;
			}
			String problem = dependency(path, file.getValue().getBytes(
					StandardCharsets.UTF_8));
			if (problem != null) {
				return problem;
			}
			written.put(path, file.getValue());
		}
		for (TextureFile texture : conversion.textures()) {
			String identity = texture.cubemap() + " " + texture.name();
			ProjectPath path = texture.path();
			if (identity.equals(written.get(path))) {
				continue;
			}
			ProjectFiles.Read read = converted.files().read(path);
			if (!(read instanceof Content content)) {
				return read instanceof ProjectFiles.Failed failed
						? failed.reason()
						: "The library texture \"" + texture.name() +
								"\" is gone from the library";
			}
			String problem = dependency(path, content.bytes());
			if (problem != null) {
				return problem;
			}
			written.put(path, identity);
		}
		return null;
	}

	private String dependency(ProjectPath path, byte[] bytes) {
		String revision = StoredFiles.revision(bytes);
		if (revision == null) {
			return "SHA-256 is not available on this device";
		}
		between.run();
		ProjectFiles.Change change = files.write(path, bytes, null);
		if (change instanceof Refused refused) {
			if (!exists(refused)) {
				return path + " cannot be written: " + refused.reason();
			}
			// Kept as it is: the move's own, also when the user
			// changed it since, or another file with those bytes.
			if (revision.equals(note.file(path))) {
				return null;
			} else if (!holds(path, bytes)) {
				return path + " is another file than the one this " +
						"shader reads; rename it, then the shader is " +
						"moved at the next start";
			}
		}
		between.run();
		return revision.equals(note.file(path)) ||
				note.noteFile(revision, path) ? null : NOTE_FAILED;
	}

	// The name noted for the shader, or a new one noted now; null
	// when it cannot be noted.
	private String name(Shader shader, Naming names) {
		String name = note.name(shader.id());
		if (name == null) {
			name = names.unused(Naming.stem(shader.title(), untitled),
					ShaderLanguage.PASS);
			ProjectParseResult<ProjectPath> path = ProjectPath.parse(name);
			if (!path.isSuccess() || !path.value().toString().equals(name)) {
				name = names.unused(untitled, ShaderLanguage.PASS);
			}
			between.run();
			if (!note.noteName(shader.id(), name)) {
				return null;
			}
		}
		names.take(name);
		return name;
	}

	// Write the shader's file, picture and settings, and note it as
	// moved. Return its path, or null when that cannot be noted.
	private ProjectPath shader(Shader shader, Converted converted,
			byte[] bytes, String name, Naming names) {
		ProjectParseResult<ProjectPath> parsed = ProjectPath.parse(name);
		if (!parsed.isSuccess()) {
			throw new Stop(name + " is not a file name");
		}
		ProjectPath path = parsed.value();
		between.run();
		ProjectFiles.Change change = files.write(path, bytes, null);
		String revision = StoredFiles.revision(bytes);
		if (change instanceof Refused refused) {
			if (!exists(refused)) {
				throw new Stop(path + " cannot be written: " +
						refused.reason());
			} else if (!holds(path, bytes)) {
				// A noted name is no proof that the file under it is
				// this shader: only its text is. Another file stays as
				// it is, and the shader gets another name.
				String next = names.unused(Naming.stem(shader.title(),
						untitled), ShaderLanguage.PASS);
				between.run();
				throw new Stop(note.noteName(shader.id(), next)
						? path + " is another file than this shader; " +
								"it is moved as " + next + " at the " +
								"next start"
						: NOTE_FAILED);
			}
		}
		between.run();
		if (stamp != null && shader.modified() > 0) {
			stamp.modified(path, shader.modified());
		}
		between.run();
		if (converted.thumbnail() != null && !previews.put(path, revision,
				converted.thumbnail())) {
			throw new Stop("the picture of " + path +
					" cannot be kept");
		}
		between.run();
		if (!settings.setQuality(path, shader.quality()) ||
				(shader.id() == chosen.template() &&
						!settings.setTemplate(path))) {
			throw new Stop("the settings of " + path +
					" cannot be kept");
		}
		between.run();
		return note.noteMoved(shader.id(), name) ? path : null;
	}

	// What the library shows when it is first opened: the file of the
	// shader that was open last. Settled once, by the first run that
	// came this far; later ones leave the library as the user has it.
	private void select() {
		if (note.selected()) {
			return;
		}
		ProjectPath path = chosen.lastOpened() > 0
				? note.moved(chosen.lastOpened())
				: null;
		between.run();
		if (path == null || selector == null || selector.select(path)) {
			between.run();
			note.noteSelected(path != null ? chosen.lastOpened() : 0);
		}
	}

	private boolean holds(ProjectPath path, byte[] bytes) {
		return files.read(path) instanceof Content content &&
				Arrays.equals(content.bytes(), bytes);
	}

	private static boolean exists(Refused refused) {
		return refused.refusal() == ProjectFiles.Refusal.EXISTS ||
				refused.refusal() == ProjectFiles.Refusal.CONFLICT;
	}
}
