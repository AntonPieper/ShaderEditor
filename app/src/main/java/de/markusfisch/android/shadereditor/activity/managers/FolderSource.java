package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.FileMarks;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Order;
import de.markusfisch.android.shadereditor.navigation.Reading;
import de.markusfisch.android.shadereditor.navigation.Recent;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Source;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ProjectSessions;
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
	}

	private final Context context;
	private final ProjectHolds holds;
	private final Opener opener;
	private final Executor listings;
	private final Handler handler;
	private final String key;
	private final FoldersSource folders;
	private final Runnable changed;
	// How far each folder is read, by its path; the root is "".
	private final Map<String, Reading> readings = new HashMap<>();
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
	 * @param changed called when what a level lists changed
	 */
	FolderSource(@NonNull Context context, @NonNull ProjectHolds holds,
			@NonNull Executor listings, @NonNull Handler handler,
			@NonNull String key, @NonNull FoldersSource folders,
			@NonNull Opener opener, @NonNull Runnable changed) {
		this.context = context;
		this.holds = holds;
		this.opener = opener;
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
				return FolderSource.this.list(level);
			}

			@Override
			public boolean open(Row row, boolean run) {
				// Whether it runs is decided once its text is known.
				var path = ProjectPath.parse(row.key());
				return path.isSuccess() && opener.open(path.value(), file);
			}

			@Override
			public void act(Row row, Row.Action action) {
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
	private Listing list(@NonNull Level level) {
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
		String title = name != null ? name : "";
		if (level.path() != null) {
			title += " / " + level.path().replace("/", " / ");
		}
		return new Listing(title,
				location == null || location.isEmpty() ? null : location,
				false,
				false,
				// A single file's folder can be opened to load the rest.
				file ? Listing.Bar.OPEN_FOLDER : Listing.Bar.NEW,
				List.of(new Listing.Section(null, FileMarks.unsaved(
						reading.rows(level,
								context.getString(R.string.nav_show_more),
								context.getString(R.string.nav_shown_of)),
						unsaved()))),
				reading.notice());
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
				handler.post(() -> listed(path, items, reachable, access));
			});
		} catch (RejectedExecutionException e) {
			// Closed.
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
		}
		Uri uri = Uri.parse(locator.value());
		boolean granted = UserFilesProvider.AUTHORITY.equals(
				uri.getAuthority());
		for (UriPermission permission :
				context.getContentResolver().getPersistedUriPermissions()) {
			granted |= permission.getUri().equals(uri) &&
					permission.isReadPermission();
		}
		return new Access(granted,
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
					item.folder()));
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
