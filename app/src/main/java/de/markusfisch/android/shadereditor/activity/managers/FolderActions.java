package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Naming;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;

/**
 * Does what the menu of a row of a folder offers. A file is duplicated
 * with the text it has now, unsaved or not, on the queue of the
 * projects. Call it on the main thread.
 */
final class FolderActions {
	/** What the drawer does for the actions. */
	interface Host {
		/** Open the file at path of key, as a tap does. */
		void open(@NonNull String key, @NonNull ProjectPath path);

		/** Show the New sheet for folder of key. */
		void showNew(@NonNull String key, @NonNull ProjectPath folder,
				@NonNull String where);

		/** The folder that holds path changed. */
		void changed(@NonNull String key, @NonNull ProjectPath path);

		/** Tell what failed, where it can be read over the drawer. */
		void tell(@NonNull String message);
	}

	private final Activity activity;
	private final Projects projects;
	private final Host host;

	FolderActions(@NonNull Activity activity, @NonNull Projects projects,
			@NonNull Host host) {
		this.activity = activity;
		this.projects = projects;
		this.host = host;
	}

	void act(@NonNull FolderSource source, @NonNull Level level,
			@NonNull Row row, @NonNull Row.Action action) {
		OpenProject project = source.project();
		var parsed = ProjectPath.parse(row.key());
		if (project == null || !parsed.isSuccess()) {
			return;
		}
		ProjectPath path = parsed.value();
		String key = source.key();
		switch (action) {
			case RUN -> {
				project.runThisFile(path);
				host.open(key, path);
			}
			case NEW_HERE -> host.showNew(key, path,
					source.title(level) + " / " + row.name());
			case DUPLICATE -> duplicate(key, project, path,
					source.names(level));
			default -> {
			}
		}
	}

	// A copy of the text it has now, unsaved or not, beside it.
	private void duplicate(@NonNull String key, @NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull List<String> names) {
		ProjectPath copy = sibling(path,
				new Naming(names).copyOf(path.getFileName()));
		if (copy == null) {
			return;
		}
		project.copyTo(new ProjectFiles() {
			@Override
			public Read read(ProjectPath read) {
				return null;
			}

			@Override
			public Change write(ProjectPath written, Source source,
					String expected) {
				return projects.files(key).write(copy, source, null);
			}

			@Override
			public Change write(ProjectPath written, byte[] bytes,
					String expected) {
				return projects.files(key).write(copy, bytes, null);
			}
		}, List.of(path), problems -> {
			String problem = problems.get(path);
			if (problem != null) {
				host.tell(activity.getString(R.string.nav_cannot_duplicate,
						path.getFileName(), problem));
			}
			host.changed(key, copy);
		});
	}

	@Nullable
	private static ProjectPath sibling(@NonNull ProjectPath path,
			@NonNull String name) {
		String at = path.toString();
		int slash = at.lastIndexOf('/');
		var parsed = ProjectPath.parse(slash < 0
				? name
				: at.substring(0, slash + 1) + name);
		return parsed.isSuccess() ? parsed.value() : null;
	}
}
