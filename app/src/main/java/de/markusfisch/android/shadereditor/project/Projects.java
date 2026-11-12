package de.markusfisch.android.shadereditor.project;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Kind;
import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/**
 * The projects the app opened: folders granted through the system
 * picker and folders of its own files. Each is a session with its
 * drafts, listed among the recent projects, so screens pass only its
 * id and a project reopens with its drafts also after the process
 * died. Removing one releases its grant and drafts, never its files.
 * Use it off the main thread.
 */
public final class Projects {
	/** An opened project with its session id, or why it is closed. */
	public record Opened(String id, OpenProject project, String problem) {
	}

	private final Context context;
	private final ProjectSessions sessions;

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
	 * Open the project of session id again, with the drafts it kept.
	 */
	public Opened open(String id) {
		Locator locator = sessions.locator(id);
		SafProjects.Opened opened = locator != null
				? SafProjects.open(context, locator)
				: null;
		OpenProject project = opened != null && opened.files() != null
				? OpenProject.open(id, opened.files(), sessions)
				: null;
		if (project == null) {
			return new Opened(id, null,
					opened != null && opened.problem() != null
							? opened.problem()
							: "it is not a known project");
		}
		sessions.touch(id, System.currentTimeMillis());
		return new Opened(id, project, null);
	}

	/**
	 * Forget project id with its drafts and release its grant; its files
	 * stay where they are. Return whether nothing of it is left.
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
