package de.markusfisch.android.shadereditor.activity.managers;

import androidx.annotation.NonNull;

import java.util.IdentityHashMap;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Projects;

/**
 * The projects the screen has open, which the editor and the drawer
 * share: one is closed once neither holds it anymore, so looking into
 * another folder does not close the project of the file in the editor.
 * Call it on the main thread.
 */
public final class ProjectHolds {
	@NonNull
	private final Projects projects;
	private final Map<OpenProject, Integer> holds = new IdentityHashMap<>();
	private boolean keepOpen;

	public ProjectHolds(@NonNull Projects projects) {
		this.projects = projects;
	}

	@NonNull
	public Projects projects() {
		return projects;
	}

	/** Open session id to look into it, see {@link Projects#look}. */
	void look(@NonNull String id,
			@NonNull FileTexts.Callback<Projects.Opened> done) {
		projects.look(id, opened -> done.done(hold(opened)));
	}

	/** Open session id for the editor, see {@link Projects#open}. */
	void open(@NonNull String id,
			@NonNull FileTexts.Callback<Projects.Opened> done) {
		projects.open(id, opened -> done.done(hold(opened)));
	}

	/** Hold a project opened elsewhere, such as through the picker. */
	void hold(@NonNull OpenProject project) {
		Integer count = holds.get(project);
		holds.put(project, count != null ? count + 1 : 1);
	}

	/** Let a project go that was held once; closed when none hold it. */
	void release(@NonNull OpenProject project) {
		Integer count = holds.get(project);
		if (count == null) {
			return;
		} else if (count > 1) {
			holds.put(project, count - 1);
			return;
		}
		holds.remove(project);
		if (!keepOpen) {
			project.close();
		}
	}

	/**
	 * Keep every project open from now on, even when let go, for the
	 * screen that is made again after a configuration change.
	 */
	public void keepOpen() {
		keepOpen = true;
	}

	@NonNull
	private Projects.Opened hold(@NonNull Projects.Opened opened) {
		if (opened.project() != null) {
			hold(opened.project());
		}
		return opened;
	}
}
