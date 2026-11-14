package de.markusfisch.android.shadereditor.project;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

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
	/** An opened project with its session id, or why it is closed. */
	public record Opened(String id, OpenProject project, String problem) {
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
		live().open(id, locator -> SafProjects.open(context, locator),
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
