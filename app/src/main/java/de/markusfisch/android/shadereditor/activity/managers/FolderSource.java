package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Handler;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Face;
import de.markusfisch.android.shadereditor.navigation.FileActions;
import de.markusfisch.android.shadereditor.navigation.FileMarks;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Order;
import de.markusfisch.android.shadereditor.navigation.Reading;
import de.markusfisch.android.shadereditor.navigation.Recent;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Source;
import de.markusfisch.android.shadereditor.navigation.Tap;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectManifest;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ProjectSessions;
import de.markusfisch.android.shadereditor.project.SafProjects;
import de.markusfisch.android.shadereditor.project.ShaderLanguage;
import de.markusfisch.android.shadereditor.provider.UserFilesProvider;

/**
 * The levels of one folder or file opened before. Its project opens
 * when a level is first listed, and each folder is read off the main
 * thread when it is entered; looking starts nothing else. A folder that
 * cannot be listed because its files are gone or its grant is lost is
 * lost and has to be chosen again. Call it on the main thread.
 */
final class FolderSource {
	// Whether what was opened can still be read, whether it is a single
	// file, and where it is, for choosing it again.
	private record Access(boolean granted, boolean file, Uri uri) {
	}

	/** Opens a file of it in the editor. */
	interface Opener {
		/**
		 * Return whether the file is being opened, so that the drawer
		 * closes.
		 *
		 * @param single whether what was opened is a single file
		 */
		boolean open(@NonNull ProjectPath path, boolean single);

		/** Say that the file named name is a picture, not text. */
		void picture(@NonNull String name);
	}

	/**
	 * What a list shows of the files of a folder besides their names
	 * and when each was changed: the pictures of its shaders that ran,
	 * and in the library the file the wallpaper shows. Read off the
	 * main thread.
	 */
	interface Looks {
		/** Return the picture of the file at path, or null. */
		@Nullable
		byte[] picture(@NonNull ProjectPath path);

		/** Return the file the wallpaper shows, or null. */
		@Nullable
		ProjectPath wallpaper();
	}

	/** Does what the menu of a row offers. */
	interface Actor {
		void act(@NonNull FolderSource source, @NonNull Level level,
				@NonNull Row row, @NonNull Row.Action action);
	}

	private final Context context;
	private final ProjectHolds holds;
	private final Opener opener;
	private final Actor actor;
	private final Executor listings;
	private final Handler handler;
	private final String key;
	private final FoldersSource folders;
	private final Runnable changed;
	@NonNull
	private Looks looks;
	// The pictures of the files read, by path, and the wallpaper's file.
	private final Map<String, byte[]> pictures = new HashMap<>();
	private String wallpaper;
	// The file the manifest at the root names to run, or null.
	private String manifestRuns;
	// How far each folder is read, by its path; the root is "".
	private final Map<String, Reading> readings = new HashMap<>();
	// The paths of the folders that can be changed.
	private final Set<String> writable = new HashSet<>();
	private String name;
	private String location;
	private OpenProject project;
	private String problem;
	private boolean opening;
	private boolean closed;
	private boolean file;
	private Uri uri;

	/**
	 * @param folders where it is listed under Recent and told when it
	 *                is lost
	 * @param opener  opens its files in the editor
	 * @param actor   does what the menus of its rows offer
	 * @param looks   what its rows show besides names
	 * @param changed called when what a level lists changed
	 */
	FolderSource(@NonNull Context context, @NonNull ProjectHolds holds,
			@NonNull Executor listings, @NonNull Handler handler,
			@NonNull String key, @NonNull FoldersSource folders,
			@NonNull Opener opener, @NonNull Actor actor,
			@NonNull Looks looks, @NonNull Runnable changed) {
		this.looks = looks;
		this.context = context;
		this.holds = holds;
		this.opener = opener;
		this.actor = actor;
		this.listings = listings;
		this.handler = handler;
		this.key = key;
		this.folders = folders;
		this.changed = changed;
		Recent known = folders.find(key);
		if (known != null) {
			name = known.name();
			location = known.location();
			file = known.file();
		}
	}

	@NonNull
	String key() {
		return key;
	}

	@NonNull
	String name() {
		return name != null ? name : "";
	}

	/** Whether it is a single file rather than a folder. */
	boolean isFile() {
		return file;
	}

	/** Its project, once it is open, else null. */
	@Nullable
	OpenProject project() {
		return project;
	}

	/** The names in the folder of level, as far as it was read. */
	@NonNull
	List<String> names(@NonNull Level level) {
		List<String> names = new ArrayList<>();
		Reading reading = readings.get(path(level));
		if (reading != null) {
			for (Reading.Entry entry : reading.entries()) {
				names.add(entry.name());
			}
		}
		return names;
	}

	/** The name of the folder of level, with its path from the root. */
	@NonNull
	String title(@NonNull Level level) {
		String title = name != null ? name : "";
		return level.path() != null
				? title + " / " + level.path().replace("/", " / ")
				: title;
	}

	/** Where it was, to start choosing it again from, or null. */
	@Nullable
	Uri uri() {
		return uri;
	}

	/**
	 * Takes the project the picker just opened, or why it is not, and
	 * whether it is a single file.
	 */
	void adopt(@Nullable OpenProject project, @Nullable String problem,
			boolean file) {
		this.file = file;
		if (project != null) {
			holds.hold(project);
		}
		readings.clear();
		opened(project, problem);
	}

	@NonNull
	Source at(@NonNull Level level) {
		return new Source() {
			@Override
			public Listing list(Order order) {
				return FolderSource.this.list(level, order);
			}

			@Override
			public boolean open(Row row, boolean run) {
				// Whether it runs is decided once its text is known.
				var path = ProjectPath.parse(row.key());
				OpenProject in = project;
				if (!path.isSuccess()) {
					return false;
				} else if (in == null) {
					return opener.open(path.value(), file);
				}
				// What it is comes from its bytes; its name only chose the
				// icon.
				Tap hinted = Tap.of(row.kind(), false);
				in.picture(path.value(), picture -> {
					if (closed) {
						return;
					} else if (hinted.read(picture) == Tap.SHOW_IMAGE) {
						opener.picture(row.name());
					} else {
						opener.open(path.value(), file);
					}
				});
				return false;
			}

			@Override
			public void act(Row row, Row.Action action) {
				actor.act(FolderSource.this, level, row, action);
			}
		};
	}

	void more(@NonNull Level level) {
		Reading reading = readings.get(path(level));
		if (reading != null) {
			put(path(level), reading.more());
		}
	}

	void stop(@NonNull Level level) {
		Reading reading = readings.get(path(level));
		if (reading != null) {
			put(path(level), reading.stopped());
		}
	}

	void retry(@NonNull Level level) {
		readings.remove(path(level));
		changed.run();
	}

	/**
	 * Takes what its rows show besides names when it is known only
	 * after the source was made, as the library's wallpaper is after a
	 * start: what was read is read again.
	 */
	void setLooks(@NonNull Looks looks) {
		if (this.looks != looks) {
			this.looks = looks;
			forget();
		}
	}

	/** Forgets every folder read, so that each is read again. */
	void forget() {
		readings.clear();
		changed.run();
	}

	/**
	 * Forgets the folders that are not level or above it, so that they
	 * are read again when entered again.
	 */
	void keep(@NonNull Level level) {
		List<String> kept = new ArrayList<>();
		for (Level at = level; at != null && key.equals(at.key());
				at = at.up()) {
			kept.add(path(at));
		}
		readings.keySet().retainAll(kept);
	}

	/** Lets the project go; nothing it does later shows anymore. */
	void close() {
		closed = true;
		if (project != null) {
			holds.release(project);
			project = null;
		}
	}

	@NonNull
	private Listing list(@NonNull Level level, @NonNull Order order) {
		String path = path(level);
		Reading reading = readings.get(path);
		if (reading == null) {
			reading = Reading.started();
			readings.put(path, reading);
			// After this listing is shown, since reading changes it.
			handler.post(() -> read(path));
			handler.postDelayed(() -> {
				Reading slow = readings.get(path);
				if (!closed && slow != null) {
					put(path, slow.slow());
				}
			}, Reading.SLOW_MILLIS);
		}
		// A level is sorted whole, then cut into pages.
		List<Row> rows = dressed(reading.rows(level,
				context.getString(R.string.nav_show_more),
				context.getString(R.string.nav_shown_of), order), reading);
		return new Listing(title(level),
				location == null || location.isEmpty() ? null : location,
				false,
				true,
				// A single file's folder can be opened to load the rest;
				// nothing can be created in a folder that is lost.
				file ? Listing.Bar.OPEN_FOLDER : reading.isLost()
						? Listing.Bar.NONE
						: Listing.Bar.NEW,
				List.of(new Listing.Section(null, FileMarks.unsaved(
						FileActions.of(rows, running(), runs(rows),
								!file && writable.contains(path)),
						unsaved()))),
				reading.notice());
	}

	// The rows with their faces, when they were changed, and the
	// wallpaper's mark; a file that cannot be opened says so with its
	// size, and the manifest says what it runs.
	@NonNull
	private List<Row> dressed(@NonNull List<Row> rows,
			@NonNull Reading reading) {
		long now = System.currentTimeMillis();
		Map<String, Long> sizes = new HashMap<>();
		for (Reading.Entry entry : reading.entries()) {
			sizes.put(entry.name(), entry.size());
		}
		List<Row> dressed = new ArrayList<>(rows.size());
		for (Row row : rows) {
			if (row.kind() == Row.Kind.MORE) {
				dressed.add(row);
				continue;
			} else if (row.kind() == Row.Kind.FOLDER) {
				dressed.add(new Row(row.key(), row.kind(),
						Face.of(row.kind(), row.name(),
								pictures.get(row.key())),
						row.name(), row.detail(), row.changed(), row.enters(),
						row.marks(), row.actions()));
				continue;
			}
			Set<Row.Mark> marks = EnumSet.noneOf(Row.Mark.class);
			marks.addAll(row.marks());
			if (row.key().equals(wallpaper)) {
				marks.add(Row.Mark.WALLPAPER);
			}
			Long size = sizes.get(row.name());
			String detail = row.kind() == Row.Kind.OTHER
					? context.getString(R.string.nav_cannot_open_size,
							Formatter.formatShortFileSize(context,
									size != null ? size : 0))
					: manifestRuns != null &&
					row.key().equals(ProjectManifest.FILE.toString())
					? context.getString(R.string.nav_runs, manifestRuns)
					: ChangeTimes.describe(context, row.changed(), now);
			dressed.add(new Row(row.key(), row.kind(),
					Face.of(row.kind(), row.name(), pictures.get(row.key())),
					row.name(), detail, row.changed(), row.enters(), marks,
					row.actions()));
		}
		return dressed;
	}

	// What is known of whether the files of rows can run: a name of a
	// language that does not run says so, and the text of a file in
	// memory does; of any other file it is told when it runs.
	@NonNull
	private Map<String, OpenProject.Runs> runs(@NonNull List<Row> rows) {
		Map<String, OpenProject.Runs> runs = new HashMap<>();
		ShaderLanguage language;
		for (Row row : rows) {
			var path = ProjectPath.parse(row.key());
			if (row.kind() == Row.Kind.FOLDER || !path.isSuccess()) {
				continue;
			} else if ((language = ShaderLanguage.listed(row.name())) !=
					null && !language.runs()) {
				runs.put(row.key(), OpenProject.Runs.NO);
			} else if (project != null &&
					project.document(path.value()) != null) {
				runs.put(row.key(), project.runs(path.value()));
			}
		}
		return runs;
	}

	@Nullable
	private String running() {
		ProjectPath running = project != null ? project.running() : null;
		return running != null ? running.toString() : null;
	}

	// The files with unsaved text, by their paths.
	@NonNull
	private Set<String> unsaved() {
		Set<String> unsaved = new HashSet<>();
		if (project != null) {
			for (ProjectPath path : project.unsaved()) {
				unsaved.add(path.toString());
			}
		}
		return unsaved;
	}

	private void read(@NonNull String path) {
		if (closed || !readings.containsKey(path)) {
			return;
		} else if (project == null && problem == null) {
			if (!opening) {
				opening = true;
				holds.look(key, opened -> opened(opened.project(),
						opened.problem()));
			}
			return;
		}
		OpenProject listed = project;
		String closedFor = problem;
		boolean reachable = listed != null && listed.storage().access() !=
				OpenProject.Access.UNAVAILABLE;
		ProjectPath folder = path.isEmpty()
				? null
				: ProjectPath.parse(path).value();
		try {
			listings.execute(() -> {
				OpenProject.Folder items = listed != null
						? listed.list(folder)
						: new OpenProject.Folder(List.of(), closedFor);
				Access access = items.problem() != null ? access() : null;
				boolean changes = listed != null && reachable &&
						listed.offers(folder).create();
				Map<String, byte[]> read = new HashMap<>();
				for (OpenProject.Item item : items.items()) {
					// A folder shows its default shader's picture.
					ProjectPath shows = item.folder()
							? ProjectPath.parse(item.path() + "/" +
									OpenProject.MAIN).value()
							: item.path();
					byte[] png = shows != null ? looks.picture(shows) : null;
					if (png != null) {
						read.put(item.path().toString(), png);
					}
				}
				ProjectPath wallpaperFile = looks.wallpaper();
				ProjectPath runs = folder == null && listed != null
						? listed.manifestEntry()
						: null;
				handler.post(() -> {
					// What was read replaces the folder's pictures: a
					// file made again under a gone file's name never shows
					// the gone file's picture.
					forgetPictures(path);
					pictures.putAll(read);
					if (folder == null) {
						manifestRuns = runs != null ? runs.toString() : null;
					}
					wallpaper = wallpaperFile != null
							? wallpaperFile.toString()
							: null;
					if (changes) {
						writable.add(path);
					} else {
						writable.remove(path);
					}
					listed(path, items, reachable, access);
				});
			});
		} catch (RejectedExecutionException e) {
			// Closed.
		}
	}

	// The pictures of the files directly in the folder at path.
	private void forgetPictures(@NonNull String path) {
		for (Iterator<String> it = pictures.keySet().iterator();
				it.hasNext(); ) {
			String file = it.next();
			int slash = file.lastIndexOf('/');
			if ((slash > -1 ? file.substring(0, slash) : "").equals(path)) {
				it.remove();
			}
		}
	}

	// Off the main thread.
	@NonNull
	private Access access() {
		ProjectSessions.Locator locator = holds.projects().sessions()
				.locator(key);
		if (locator == null) {
			return new Access(false, false, null);
		} else if (locator.kind() == ProjectSessions.Kind.DIRECTORY) {
			return new Access(new File(locator.value()).isDirectory(),
					false, null);
		} else if (locator.kind() == ProjectSessions.Kind.EXAMPLES) {
			return new Access(true, false, null);
		}
		Uri uri = Uri.parse(locator.value());
		boolean granted = UserFilesProvider.AUTHORITY.equals(
				uri.getAuthority());
		for (UriPermission permission :
				context.getContentResolver().getPersistedUriPermissions()) {
			granted |= permission.getUri().equals(uri) &&
					permission.isReadPermission();
		}
		// A folder or file deleted under a project that opened before
		// keeps its grant; it is lost all the same.
		return new Access(granted && SafProjects.open(context, locator)
				.problem() == null,
				locator.kind() == ProjectSessions.Kind.DOCUMENT, uri);
	}

	private void opened(@Nullable OpenProject project,
			@Nullable String problem) {
		opening = false;
		if (closed) {
			if (project != null) {
				holds.release(project);
			}
			return;
		}
		if (this.project != null) {
			holds.release(this.project);
		}
		this.project = project;
		this.problem = project != null
				? null
				: problem != null ? problem : "it cannot be opened";
		if (project != null && project.name() != null) {
			name = project.name();
			location = project.location();
		}
		for (String path : new ArrayList<>(readings.keySet())) {
			read(path);
		}
		changed.run();
	}

	private void listed(@NonNull String path,
			@NonNull OpenProject.Folder folder, boolean reachable,
			@Nullable Access access) {
		Reading reading = readings.get(path);
		if (closed || reading == null) {
			return;
		} else if (access != null) {
			file = access.file();
			uri = access.uri();
			reading = reading.failed(folder.problem(), reachable,
					access.granted(), file);
			folders.setLost(key, reading.isLost());
			put(path, reading);
			return;
		}
		folders.setLost(key, false);
		List<Reading.Entry> entries = new ArrayList<>();
		for (OpenProject.Item item : folder.items()) {
			entries.add(new Reading.Entry(item.path().getFileName(),
					item.folder(), item.modified(), item.size()));
		}
		put(path, reading.listed(entries));
	}

	private void put(@NonNull String path, @NonNull Reading reading) {
		readings.put(path, reading);
		changed.run();
	}

	@NonNull
	private static String path(@NonNull Level level) {
		return level.path() != null ? level.path() : "";
	}
}
