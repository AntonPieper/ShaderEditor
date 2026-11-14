package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.project.ProjectSessions.Locator;

/**
 * The projects open in the process, one per session: opening a session
 * that is open gives the same project until it is closed. A session
 * whose files cannot be reached opens with its unsaved text all the
 * same. The registry belongs to the main thread and holds no lock: a
 * project is opened by a task of the worker its storage work runs on,
 * so opening comes after what a project of the session that closed
 * still writes, and is handed over on main. Nothing here waits.
 */
final class LiveProjects {
	interface Opener {
		SafProjects.Opened open(Locator locator);

		/** Return what follows the files of the project at locator. */
		default FileTexts.Kept kept(Locator locator) {
			return null;
		}
	}

	private final Map<String, OpenProject> live = new HashMap<>();
	// The callbacks of the sessions being opened, which later calls
	// join.
	private final Map<String, List<FileTexts.Callback<OpenProject>>>
			opening = new HashMap<>();
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
	 * Find the project of session id, opened through opener on the
	 * worker when it is not open. Call it on main; done gets the project
	 * there, later, or null when there is no such session or opening
	 * failed.
	 */
	void open(String id, Opener opener, FileTexts.Callback<OpenProject> done) {
		OpenProject project = live.get(id);
		if (project != null) {
			main.execute(() -> {
				// Closed meanwhile: open it again.
				if (live.get(id) == project) {
					done.done(project);
				} else {
					open(id, opener, done);
				}
			});
			return;
		}
		List<FileTexts.Callback<OpenProject>> joined = opening.get(id);
		if (joined != null) {
			joined.add(done);
			return;
		}
		joined = new ArrayList<>();
		joined.add(done);
		opening.put(id, joined);
		worker.execute(() -> {
			OpenProject[] opened = {null};
			try {
				opened[0] = open(id, opener);
			} finally {
				main.execute(() -> publish(id, opener, opened[0]));
			}
		});
	}

	// On the worker; reads storage and touches nothing of the registry.
	private OpenProject open(String id, Opener opener) {
		Locator locator = sessions.locator(id);
		if (locator == null) {
			return null;
		}
		SafProjects.Opened opened = opener.open(locator);
		return OpenProject.open(id, opened.files(), opened.file(),
				opened.problem() != null
						? opened.problem()
						: "it cannot be opened",
				sessions, worker, main, later, budget,
				opener.kept(locator));
	}

	/**
	 * Return whether session id is open or being opened. On main.
	 */
	boolean isOpen(String id) {
		return live.containsKey(id) || opening.containsKey(id);
	}

	private void publish(String id, Opener opener, OpenProject project) {
		List<FileTexts.Callback<OpenProject>> joined = opening.remove(id);
		if (project != null) {
			live.put(id, project);
			project.onClose(() -> {
				if (live.get(id) == project) {
					live.remove(id);
				}
			});
		}
		for (FileTexts.Callback<OpenProject> done : joined) {
			// One told before may have closed it: nobody is handed a
			// closed project as the one they opened.
			if (project != null && live.get(id) != project) {
				open(id, opener, done);
			} else {
				done.done(project);
			}
		}
	}
}
