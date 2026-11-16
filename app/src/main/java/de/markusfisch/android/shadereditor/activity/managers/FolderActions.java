package de.markusfisch.android.shadereditor.activity.managers;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Moves;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.project.HostDirectives;
import de.markusfisch.android.shadereditor.project.Naming;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.widget.NewSheet;
import de.markusfisch.android.shadereditor.widget.RenameDialog;

/**
 * Does what the menu of a row of a folder offers. Files are renamed
 * and deleted by their project, together with what it holds of them,
 * and duplicated on the queue of the projects. Call it on the main
 * thread.
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
		// The lines of the running shader that name it change with it.
		List<HostDirectives.Reference> named = project.reads(path)
				? project.references(path)
				: List.of();
		CharSequence also = RenameReferences.sentence(activity, named);
		shown[0] = RenameDialog.show(activity, R.string.nav_rename,
				row.name(), name ->
						NewSheet.describe(activity, naming.check(name), name),
				also == null ? uses(project, path) : null, also, name -> {
					ProjectPath to = sibling(path, name);
					if (to == null || name.equals(row.name())) {
						shown[0].dismiss();
						return;
					}
					boolean follow = shown[0].also();
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
						if (moved instanceof OpenProject.Renamed && follow) {
							RenameReferences.change(project, named, path, to,
									undoable(key, project, path, to));
						}
						if (moved instanceof OpenProject.Renamed) {
							follow(project, running, path, to);
						}
						answered(key, path, moved, R.string.nav_cannot_rename);
					});
				});
	}

	// One Undo in a file whose lines the rename changed renames the file
	// back and takes back those lines in every file, or does nothing and
	// says why: a file changed since, or the old name is taken.
	@NonNull
	private RenameReferences.Rename undoable(@NonNull String key,
			@NonNull OpenProject project, @NonNull ProjectPath from,
			@NonNull ProjectPath to) {
		return new RenameReferences.Rename() {
			private boolean taking;

			@Override
			public void undo(Document document) {
				if (taking) {
					return;
				}
				for (Map.Entry<Document, ProjectPath> entry :
						changed.entrySet()) {
					Document changedOne = entry.getKey();
					ProjectPath file = entry.getValue();
					if (project.document(file) != changedOne ||
							!changedOne.endsWith(this)) {
						host.tell(activity.getString(R.string.rename_not_undone,
								from.getFileName(), file.getFileName()));
						return;
					}
				}
				taking = true;
				ProjectPath running = project.running();
				project.rename(to, from, moved -> {
					taking = false;
					if (moved instanceof OpenProject.Renamed) {
						for (Document changedOne : changed.keySet()) {
							changedOne.undoLinked(this);
						}
						follow(project, running, to, from);
						host.tell(activity.getString(R.string.rename_undone,
								from.getFileName()));
					}
					answered(key, to, moved, R.string.nav_cannot_rename);
				});
			}
		};
	}

	// What ran by the rule, as main.frag, keeps running under its new
	// name.
	private static void follow(@NonNull OpenProject project,
			@Nullable ProjectPath running, @NonNull ProjectPath from,
			@NonNull ProjectPath to) {
		if (running == null ||
				!Moves.within(running.toString(), from.toString())) {
			return;
		}
		ProjectPath runs = ProjectPath.parse(Moves.renamed(
				running.toString(), from.toString(), to.toString())).value();
		if (!runs.equals(project.running())) {
			project.runThisFile(runs);
		}
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
						Deleting.delete(activity, project, path, moved ->
								answered(key, path, moved,
										R.string.nav_cannot_delete)))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
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
