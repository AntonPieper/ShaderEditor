package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * Deletes a file or folder through its project, which deletes no
 * unsaved text it was not asked to: when files there have some, they
 * are named in a question, and only a yes deletes them, with the text
 * they had when asked. Text typed meanwhile asks again. Call it on the
 * main thread, after the user confirmed the deletion itself.
 */
final class Deleting {
	private Deleting() {
	}

	/**
	 * Delete path of project; done gets {@link OpenProject.Deleted} or
	 * {@link OpenProject.Refused}, nothing when the user kept the
	 * unsaved text.
	 */
	static void delete(@NonNull Context context, @NonNull OpenProject project,
			@NonNull ProjectPath path,
			@NonNull FileTexts.Callback<OpenProject.Moved> done) {
		delete(context, project, path, Map.of(), done);
	}

	private static void delete(@NonNull Context context,
			@NonNull OpenProject project, @NonNull ProjectPath path,
			@NonNull Map<ProjectPath, Integer> discard,
			@NonNull FileTexts.Callback<OpenProject.Moved> done) {
		project.delete(path, discard, moved -> {
			if (!(moved instanceof OpenProject.Unsaved unsaved)) {
				done.done(moved);
				return;
			}
			List<String> names = new ArrayList<>();
			for (ProjectPath file : unsaved.texts().keySet()) {
				names.add(file.toString());
			}
			Collections.sort(names);
			int count = names.size();
			new MaterialAlertDialogBuilder(context)
					.setTitle(context.getString(R.string.delete_shader_title,
							path.getFileName()))
					.setMessage(context.getResources().getQuantityString(
							R.plurals.nav_delete_unsaved, count, count,
							TextUtils.join(", ", names)))
					.setPositiveButton(R.string.nav_delete, (d, which) ->
							delete(context, project, path, unsaved.texts(),
									done))
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		});
	}
}
