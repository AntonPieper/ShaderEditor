package de.markusfisch.android.shadereditor.project;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;
import de.markusfisch.android.shadereditor.provider.UserFilesProvider;

/**
 * The projects the app opened: folders granted through the system
 * picker and folders of its own files. Each is a session with its
 * drafts, listed among the recent projects, so screens pass only its
 * id and a project reopens with its drafts also after the process
 * died. Removing one releases its grant and keeps unsaved drafts,
 * never touching its files. Call it on the main thread: everything
 * that reads or writes runs on the one queue the projects of the
 * process work on, after what was given to it before, and answers on
 * main. Nothing waits for the queue.
 */
public final class Projects {
	/** A change of the files of a project, made on the queue. */
	public interface Change {
		ProjectFiles.Change make(ProjectFiles files);
	}

	/** An opened project with its session id, or why it is closed. */
	public record Opened(String id, OpenProject project, String problem) {
	}

	/**
	 * What a redo of the move did: why the library could not be put
	 * aside, and then moved is null, or what the move into the new one
	 * did.
	 */
	public record Redone(String problem, LibraryMove.Result moved) {
	}

	// The one queue every project of the process works on storage on.
	private static final Executor QUEUE =
			Executors.newSingleThreadExecutor();
	private static LiveProjects live;

	private final Context context;
	private final ProjectSessions sessions;
	private final Handler main = new Handler(Looper.getMainLooper());

	public Projects(Context context) {
		this.context = context.getApplicationContext();
		sessions = new ProjectSessions(new File(
				this.context.getNoBackupFilesDir(), "projects"));
	}

	/** Return the sessions; use them off the main thread. */
	public ProjectSessions sessions() {
		return sessions;
	}

	/**
	 * Open a tree from ACTION_OPEN_DOCUMENT_TREE with the flags of the
	 * picker's result: keep its grant, start its session or find the
	 * one it has, and list it among the recent projects.
	 */
	public void openTree(Uri tree, int resultFlags, Callback<Opened> done) {
		QUEUE.execute(() -> {
			Locator locator = SafProjects.locate(context, tree);
			if (locator != null && locator.kind() == Kind.TREE) {
				SafProjects.keepGrant(context.getContentResolver(), tree,
						resultFlags);
			}
			openNew(locator, done);
		});
	}

	/**
	 * Open a document from ACTION_OPEN_DOCUMENT or an intent with the
	 * flags of its grant as a project of that one file, which runs
	 * whatever its name.
	 */
	public void openDocument(Uri uri, int resultFlags,
			Callback<Opened> done) {
		QUEUE.execute(() -> {
			SafProjects.keepGrant(context.getContentResolver(), uri,
					resultFlags);
			openNew(new Locator(Kind.DOCUMENT, uri.toString()), done);
		});
	}

	/**
	 * Open a folder of the app's own files like a folder the picker
	 * granted.
	 */
	public void openDirectory(File dir, Callback<Opened> done) {
		QUEUE.execute(() -> {
			Locator locator;
			try {
				locator = new Locator(Kind.DIRECTORY,
						dir.getCanonicalPath());
			} catch (IOException e) {
				locator = null;
			}
			openNew(locator, done);
		});
	}

	/**
	 * Open the library, the folder of the app's own files that holds
	 * the user's shaders, like any folder; it is made when it is not
	 * there. While a redo of the move is not finished it is not
	 * opened, and problem says why.
	 */
	public void openLibrary(Callback<Opened> done) {
		QUEUE.execute(() -> {
			String barrier = guarded(() -> library().finishRedo(
					this::relocated), problem -> problem);
			File folder = library().folder();
			if (barrier == null) {
				folder.mkdirs();
			}
			main.post(() -> {
				if (barrier != null) {
					done.done(new Opened(null, null, barrier));
				} else {
					openDirectory(folder, done);
				}
			});
		});
	}

	/**
	 * Return the library, to read from off the main thread: where it
	 * is, the settings and pictures of its files, and which file a
	 * shader of the database became. What changes it are the calls
	 * here, which run on the queue.
	 */
	public Library library() {
		return new Library(UserFilesProvider.getUserFilesDir(context),
				context.getFilesDir());
	}

	/**
	 * Move the shaders of the database that are not moved yet into the
	 * library, on the queue; done gets what became of every shader, or
	 * why nothing could be moved. The database is only read. Asking
	 * again, at every start, is cheap and moves nothing twice.
	 *
	 * @param reserved the names of the builtin uniforms
	 * @param untitled the stem of the name of a shader without a title
	 */
	public void moveLibrary(DataSource database, Set<String> reserved,
			String untitled, LibraryMove.Chosen chosen,
			Callback<LibraryMove.Result> done) {
		QUEUE.execute(() -> {
			LibraryMove.Result result = guarded(() -> move(database,
					reserved, untitled, chosen), LibraryMove.Result::blocked);
			main.post(() -> done.done(result));
		});
	}

	/**
	 * Put the library aside under another name and move every shader
	 * of the database into a new one; nothing is deleted. Call it on
	 * main. It is refused while the library is open, also without
	 * unsaved text: close its project first. The library that was put
	 * aside keeps its session, with its undo histories, and its
	 * settings and pictures lie beside it; the new one starts without
	 * any.
	 */
	public void redoLibraryMove(DataSource database, Set<String> reserved,
			String untitled, LibraryMove.Chosen chosen,
			Callback<Redone> done) {
		Locator locator = libraryLocator();
		String id = locator != null ? sessions.find(locator) : null;
		if (id != null && live().isOpen(id)) {
			done.done(new Redone("the library is open; close it first",
					null));
			return;
		}
		QUEUE.execute(() -> {
			Redone redone = guarded(() -> {
				// A journal that cannot be read counts as text too.
				ProjectSessions.Recent recent = id != null
						? sessions.recent(id)
						: null;
				String problem = id != null && (recent == null
						? !sessions.unsaved(id).isEmpty()
						: recent.unsaved())
						? "the library has unsaved text; open it and " +
								"save or discard it first"
						: library().putAside(this::relocated);
				return new Redone(problem, problem == null
						? move(database, reserved, untitled, chosen)
						: null);
			}, problem -> new Redone(problem, null));
			main.post(() -> done.done(redone));
		});
	}

	/**
	 * Keep quality for the file of the library at path, on the queue;
	 * done gets why it is not kept, or null.
	 */
	public void setQuality(ProjectPath path, float quality,
			Callback<String> done) {
		change(() -> library().settings().setQuality(path, quality)
				? null
				: "the settings of the library cannot be written", done);
	}

	/**
	 * Keep png as the picture of the file of the library at path, made
	 * from its content at revision, on the queue; done gets why it is
	 * not kept, or null.
	 */
	public void setPreview(ProjectPath path, String revision, byte[] png,
			Callback<String> done) {
		change(() -> library().previews().put(path, revision, png)
				? null
				: "the picture cannot be kept", done);
	}

	// What a task of the queue answers; nothing it throws leaves the
	// queue.
	private interface Task<T> {
		T run();
	}

	private interface Failure<T> {
		T of(String problem);
	}

	private static <T> T guarded(Task<T> task, Failure<T> failure) {
		try {
			return task.run();
		} catch (RuntimeException | OutOfMemoryError e) {
			return failure.of(e instanceof OutOfMemoryError
					? "it does not fit into memory"
					: e.getMessage() != null
							? e.getMessage()
							: e.getClass().getSimpleName());
		}
	}

	private void change(Task<String> task, Callback<String> done) {
		QUEUE.execute(() -> {
			String problem = guarded(task, reason -> reason);
			main.post(() -> done.done(problem));
		});
	}

	// On the queue.
	private LibraryMove.Result move(DataSource database,
			Set<String> reserved, String untitled,
			LibraryMove.Chosen chosen) {
		return library().move(new DatabaseBackup(database,
				ProjectLimits.DEFAULT, reserved), untitled, chosen, path -> {
			Locator locator = libraryLocator();
			String id = locator != null ? sessions.open(locator) : null;
			return id != null && sessions.setShown(id, path) &&
					sessions.setRunning(id, path);
		}, this::relocated);
	}

	// The session of the library goes with the folder that is put
	// aside, so that the new library starts a session of its own.
	private boolean relocated(File from, File to) {
		try {
			return sessions.relocate(
					new Locator(Kind.DIRECTORY, from.getCanonicalPath()),
					new Locator(Kind.DIRECTORY, to.getCanonicalPath()));
		} catch (IOException e) {
			return false;
		}
	}

	private Locator libraryLocator() {
		try {
			return new Locator(Kind.DIRECTORY,
					library().folder().getCanonicalPath());
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * Open the project of session id again, with the unsaved text it
	 * kept, and remember it as the one open last; done gets the project
	 * that is open already when there is one. A project whose files
	 * cannot be reached opens with its unsaved text, and problem tells
	 * why. It stays open until {@link OpenProject#close}.
	 */
	public void open(String id, Callback<Opened> done) {
		open(id, true, done);
	}

	/**
	 * Open the project of session id like {@link #open}, to look into
	 * its folders: it keeps its place among the recent projects and is
	 * not remembered as the one open last.
	 */
	public void look(String id, Callback<Opened> done) {
		open(id, false, done);
	}

	private void open(String id, boolean remember, Callback<Opened> done) {
		live().open(id, new LiveProjects.Opener() {
					@Override
					public SafProjects.Opened open(Locator locator) {
						return SafProjects.open(context, locator);
					}

					// The settings and pictures of the library follow
					// its files.
					@Override
					public FileTexts.Kept kept(Locator locator) {
						return locator.equals(libraryLocator())
								? library().kept()
								: null;
					}
				},
				project -> {
					if (project == null) {
						done.done(new Opened(id, null,
								"it is not a known project"));
						return;
					}
					if (remember) {
						project.remember();
					}
					done.done(new Opened(id, project,
							project.storage().reason()));
				});
	}

	private LiveProjects live() {
		synchronized (Projects.class) {
			if (live == null) {
				ActivityManager manager = (ActivityManager)
						context.getSystemService(Context.ACTIVITY_SERVICE);
				live = new LiveProjects(sessions, QUEUE, main::post,
						main::postDelayed, OpenProject.budget(
								manager.getMemoryClass() * 1024L * 1024));
			}
			return live;
		}
	}

	/**
	 * Remove project id from the recent projects and release its grant;
	 * its files stay where they are, and so do its drafts until the
	 * project is opened again and they are saved or discarded. done
	 * gets whether it is no longer listed.
	 */
	public void remove(String id, Callback<Boolean> done) {
		QUEUE.execute(() -> {
			Locator locator = sessions.locator(id);
			if (locator != null && locator.kind() == Kind.TREE) {
				SafProjects.releaseGrant(context.getContentResolver(),
						Uri.parse(locator.value()));
			}
			boolean removed = sessions.remove(id);
			main.post(() -> done.done(removed));
		});
	}

	/**
	 * Make change to the files of session id on the queue, after what
	 * was asked of its project before, so that it is one storage
	 * operation of its own; done gets what it did on main. Its project
	 * sees the change once it looks again, as with any change made
	 * elsewhere: {@link OpenProject#refresh}.
	 */
	public void change(String id, Change change,
			Callback<ProjectFiles.Change> done) {
		QUEUE.execute(() -> {
			ProjectFiles.Change made = change.make(files(id));
			main.post(() -> done.done(made));
		});
	}

	/**
	 * Return the files of session id, to be used on the queue only: in
	 * a {@link Change}, or as the destination of
	 * {@link OpenProject#copyTo}. Files that cannot be reached refuse
	 * every change.
	 */
	public ProjectFiles files(String id) {
		Locator locator = sessions.locator(id);
		SafProjects.Opened opened = locator != null
				? SafProjects.open(context, locator)
				: null;
		return opened != null && opened.files() != null
				? opened.files()
				: new ProjectFiles() {
					@Override
					public Read read(ProjectPath path) {
						return new Failed("it cannot be opened");
					}
				};
	}

	// On the queue.
	private void openNew(Locator locator, Callback<Opened> done) {
		String[] description = locator != null
				? SafProjects.describe(context, locator)
				: null;
		String id = description != null ? sessions.open(locator) : null;
		if (id == null || !sessions.remember(id, description[0],
				description[1], System.currentTimeMillis())) {
			main.post(() -> done.done(new Opened(null, null,
					"this folder cannot be opened")));
		} else {
			main.post(() -> look(id, done));
		}
	}
}
