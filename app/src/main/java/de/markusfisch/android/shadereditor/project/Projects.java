package de.markusfisch.android.shadereditor.project;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;

import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/**
 * The projects the app opened: folders granted through the system
 * picker and folders of its own files. Each is a session with its
 * drafts, listed among the recent projects, so screens pass only its
 * id and a project reopens with its drafts also after the process
 * died. Removing one releases its grant and keeps unsaved drafts,
 * never touching its files.
 * Use it off the main thread.
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

	/**
	 * Open projects on the calling worker; they work on storage on one
	 * queue of the process, a task at a time, and post results to the
	 * main thread.
	 */
	public Projects(Context context) {
		this.context = context.getApplicationContext();
		sessions = new ProjectSessions(new File(
				this.context.getNoBackupFilesDir(), "projects"));
	}

	public ProjectSessions sessions() {
		return sessions;
	}

	/**
	 * Open a tree from ACTION_OPEN_DOCUMENT_TREE with the flags of the
	 * picker's result: keep its grant, start its session or find the
	 * one it has, and list it among the recent projects.
	 */
	public Opened openTree(Uri tree, int resultFlags) {
		Locator locator = SafProjects.locate(context, tree);
		if (locator != null && locator.kind() == Kind.TREE) {
			SafProjects.keepGrant(context.getContentResolver(), tree,
					resultFlags);
		}
		return openNew(locator);
	}

	/**
	 * Open a document from ACTION_OPEN_DOCUMENT or an intent with the
	 * flags of its grant as a project of that one file, which runs
	 * whatever its name.
	 */
	public Opened openDocument(Uri uri, int resultFlags) {
		SafProjects.keepGrant(context.getContentResolver(), uri,
				resultFlags);
		return openNew(new Locator(Kind.DOCUMENT, uri.toString()));
	}

	/**
	 * Open a folder of the app's own files like a folder the picker
	 * granted.
	 */
	public Opened openDirectory(File dir) {
		Locator locator;
		try {
			locator = new Locator(Kind.DIRECTORY, dir.getCanonicalPath());
		} catch (IOException e) {
			locator = null;
		}
		return openNew(locator);
	}

	/**
	 * Open the project of session id again, with the unsaved text it
	 * kept, and remember it as the one open last; return the project
	 * when it is open already. A project whose files cannot be reached
	 * opens with its unsaved text, and problem tells why. It stays open
	 * until {@link OpenProject#close}. Waits for the queue the projects
	 * work on, so it is never called from there.
	 */
	public Opened open(String id) {
		OpenProject[] opened = {null};
		CountDownLatch done = new CountDownLatch(1);
		live().open(id, locator -> SafProjects.open(context, locator),
				project -> {
					opened[0] = project;
					done.countDown();
				});
		try {
			done.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new Opened(id, null, "opening was interrupted");
		}
		OpenProject project = opened[0];
		if (project == null) {
			return new Opened(id, null, "it is not a known project");
		}
		sessions.touch(id, System.currentTimeMillis());
		sessions.setLast(id);
		return new Opened(id, project, project.storage().reason());
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
	 * project is opened again and they are saved or discarded. Return
	 * whether it is no longer listed.
	 */
	public boolean remove(String id) {
		Locator locator = sessions.locator(id);
		if (locator != null && locator.kind() == Kind.TREE) {
			SafProjects.releaseGrant(context.getContentResolver(),
					Uri.parse(locator.value()));
		}
		return sessions.remove(id);
	}

	private Opened openNew(Locator locator) {
		String[] description = locator != null
				? SafProjects.describe(context, locator)
				: null;
		String id = description != null ? sessions.open(locator) : null;
		if (id == null || !sessions.remember(id, description[0],
				description[1], System.currentTimeMillis())) {
			return new Opened(null, null, "this folder cannot be opened");
		}
		return open(id);
	}
}
