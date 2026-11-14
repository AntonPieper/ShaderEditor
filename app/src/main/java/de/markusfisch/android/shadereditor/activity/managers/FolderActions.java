package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Moves;
import de.markusfisch.android.shadereditor.navigation.Naming;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.widget.NewSheet;
import de.markusfisch.android.shadereditor.widget.RenameDialog;

/**
 * Does what the menu of a row of a folder offers. Files are renamed,
 * duplicated and deleted on the queue of the projects, as one change
 * each, and the project then looks at its files again. A file the
 * project holds, open in the editor or with unsaved text, is neither
 * renamed nor deleted, so no unsaved text is ever lost with it. Call
 * it on the main thread.
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
			case RENAME -> rename(key, project, path, row,
					source.names(level));
			case DELETE -> delete(key, project, path, row);
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

	private void rename(@NonNull String key, @NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Row row,
			@NonNull List<String> names) {
		names.remove(row.name());
		Naming naming = new Naming(names);
		RenameDialog.Shown[] shown = {null};
		shown[0] = RenameDialog.show(activity, R.string.nav_rename,
				row.name(), name ->
						NewSheet.describe(activity, naming.check(name), name),
				uses(project, path), name -> {
					ProjectPath to = sibling(path, name);
					if (to == null || name.equals(row.name())) {
						shown[0].dismiss();
						return;
					}
					// What the project holds there, the open file too,
					// follows; the editor learns it from the project.
					ProjectPath running = project.running();
					project.rename(path, to, moved -> {
						if (moved instanceof OpenProject.Refused refused &&
								refused.refusal() ==
										ProjectFiles.Refusal.EXISTS) {
							shown[0].fail(activity.getString(
									R.string.nav_name_taken, name));
							return;
						}
						shown[0].dismiss();
						// What ran by the rule, as main.glsl, keeps running
						// under its new name.
						if (moved instanceof OpenProject.Renamed &&
								running != null && Moves.within(
								running.toString(), path.toString())) {
							ProjectPath runs = ProjectPath.parse(
									Moves.renamed(running.toString(),
											path.toString(), to.toString()))
									.value();
							if (!runs.equals(project.running())) {
								project.runThisFile(runs);
							}
						}
						answered(key, path, moved, R.string.nav_cannot_rename);
					});
				});
	}

	// What a rename or deletion did, told and shown in the list.
	private void answered(@NonNull String key, @NonNull ProjectPath path,
			@NonNull OpenProject.Moved moved, int failed) {
		if (moved instanceof OpenProject.Refused refused) {
			host.tell(activity.getString(failed, path.getFileName(),
					refused.reason()));
			if (refused.refusal() != ProjectFiles.Refusal.ABSENT &&
					refused.refusal() != ProjectFiles.Refusal.PARTIAL) {
				return;
			}
		}
		host.changed(key, path);
	}

	private void delete(@NonNull String key, @NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Row row) {
		if (refuse(project, path, row, R.string.nav_cannot_delete_held,
				R.string.nav_cannot_delete_holding)) {
			return;
		}
		CharSequence uses = uses(project, path);
		new MaterialAlertDialogBuilder(activity)
				.setTitle(activity.getString(R.string.delete_shader_title,
						row.name()))
				.setMessage(uses != null
						? uses
						: row.kind() == Row.Kind.FOLDER
						? activity.getString(R.string.nav_delete_folder)
						: null)
				.setPositiveButton(R.string.nav_delete, (d, which) ->
						change(key, project, path, R.string.nav_cannot_delete,
								files -> files.delete(path), null))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void change(@NonNull String key, @NonNull OpenProject project,
			@NonNull ProjectPath path, int failed,
			@NonNull Projects.Change change, @Nullable Runnable done) {
		projects.change(key, change, made -> {
			if (made instanceof ProjectFiles.Refused refused) {
				host.tell(activity.getString(failed, path.getFileName(),
						refused.reason()));
				return;
			}
			if (done != null) {
				done.run();
			}
			// It sees the change as one made elsewhere.
			project.refresh(reread -> {
			});
			host.changed(key, path);
		});
	}

	// The project holds a file while it is open in the editor or kept
	// in memory, or has unsaved text; changing it would leave the
	// project's copy behind.
	private boolean refuse(@NonNull OpenProject project,
			@NonNull ProjectPath path, @NonNull Row row, int file,
			int folder) {
		boolean held;
		if (row.kind() == Row.Kind.FOLDER) {
			held = project.marks(path).unsaved() > 0 ||
					under(project.shown(), path);
		} else {
			held = project.document(path) != null ||
					project.unsaved().contains(path) ||
					!project.settled(path);
		}
		if (held) {
			host.tell(activity.getString(row.kind() == Row.Kind.FOLDER
					? folder
					: file, row.name()));
		}
		return held;
	}

	// What the running shader would miss, said before it happens.
	@Nullable
	private CharSequence uses(@NonNull OpenProject project,
			@NonNull ProjectPath path) {
		ProjectPath running = project.running();
		return running != null && !running.equals(path) &&
				project.uses(path)
				? activity.getString(R.string.nav_used_by,
						running.getFileName())
				: null;
	}

	private static boolean under(@Nullable ProjectPath file,
			@NonNull ProjectPath folder) {
		return file != null && file.toString().startsWith(folder + "/");
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
