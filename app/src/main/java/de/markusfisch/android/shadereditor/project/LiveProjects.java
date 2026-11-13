package de.markusfisch.android.shadereditor.project;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/**
 * The projects open in the process, one per session: opening a session
 * that is open returns the same project until it is closed. A session
 * whose files cannot be reached opens with its unsaved text all the
 * same. Projects are opened on the worker their storage work runs on,
 * so opening comes after what a project of the session that closed
 * still writes.
 */
final class LiveProjects {
	interface Opener {
		SafProjects.Opened open(Locator locator);
	}

	private final Map<String, OpenProject> live = new HashMap<>();
	private final ProjectSessions sessions;
	private final Executor worker;
	private final Executor main;
	private final OpenProject.Later later;
	private final long budget;

	LiveProjects(ProjectSessions sessions, Executor worker, Executor main,
			OpenProject.Later later, long budget) {
		this.sessions = sessions;
		this.worker = worker;
		this.main = main;
		this.later = later;
		this.budget = budget;
	}

	/**
	 * Find the project of session id, opened through opener when it is
	 * not open, on the worker after what it was given before; done gets
	 * it there, or null when there is no such session or opening failed.
	 */
	void open(String id, Opener opener, FileTexts.Callback<OpenProject> done) {
		worker.execute(() -> {
			OpenProject project = null;
			try {
				project = open(id, opener);
			} finally {
				done.done(project);
			}
		});
	}

	private synchronized OpenProject open(String id, Opener opener) {
		OpenProject project = live.get(id);
		if (project != null) {
			return project;
		}
		Locator locator = sessions.locator(id);
		if (locator == null) {
			return null;
		}
		SafProjects.Opened opened = opener.open(locator);
		project = OpenProject.open(id, opened.files(), opened.file(),
				opened.problem() != null
						? opened.problem()
						: "it cannot be opened",
				sessions, worker, main, later, budget);
		if (project != null) {
			OpenProject opening = project;
			project.onClose(() -> close(id, opening));
			live.put(id, project);
		}
		return project;
	}

	private synchronized void close(String id, OpenProject project) {
		if (live.get(id) == project) {
			live.remove(id);
		}
	}
}
