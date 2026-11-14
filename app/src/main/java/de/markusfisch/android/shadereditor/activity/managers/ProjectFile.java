package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.FileLine;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * A file of a folder, or a single file, open in the editor. Its text is
 * the project's document of it, which the project keeps, journals and
 * saves; whether it is unsaved is the project's to say. It can only be
 * saved, with every other file of the project that has unsaved text.
 */
public final class ProjectFile implements OpenItem {
	/** What the activity does when the file has news. */
	public interface Listener {
		/** The title, the second line or the unsaved state changed. */
		void onChanged();

		/** An explicit save wrote every file. */
		void onSaved();

		/** Something failed, for the reason given. */
		void onFailed(@NonNull String message);
	}

	@NonNull
	private final Context context;
	@NonNull
	private final OpenProject project;
	@NonNull
	private final ProjectPath path;
	@NonNull
	private final String folder;
	@NonNull
	private final Listener listener;
	private final boolean single;
	private float quality = 1f;

	/**
	 * @param single whether the project is a single file rather than a
	 *               folder
	 */
	public ProjectFile(@NonNull Context context, @NonNull OpenProject project,
			@NonNull ProjectPath path, boolean single,
			@NonNull Listener listener) {
		this.context = context;
		this.project = project;
		this.path = path;
		this.listener = listener;
		this.single = single;
		String name = project.name();
		folder = single || name == null
				? context.getString(R.string.nav_single_file)
				: name;
	}

	@NonNull
	public OpenProject getProject() {
		return project;
	}

	@NonNull
	public ProjectPath getPath() {
		return path;
	}

	/** Return whether its project is a single file. */
	public boolean isSingle() {
		return single;
	}

	@NonNull
	@Override
	public String getTitle() {
		return path.getFileName();
	}

	@Nullable
	@Override
	public String getSubtitle() {
		ProjectPath running = project.running();
		return FileLine.of(folder, path.toString(),
				running != null ? running.toString() : null);
	}

	@Override
	public boolean isModified() {
		return project.unsaved().contains(path);
	}

	@Override
	public void onTextModified() {
		listener.onChanged();
	}

	@Override
	public float getQuality() {
		return quality;
	}

	// Not kept with the file.
	@Override
	public void setQuality(float quality) {
		this.quality = quality;
	}

	@Override
	public boolean can(@NonNull Action action) {
		return action == Action.SAVE;
	}

	@Override
	public boolean offers(@NonNull Action action) {
		return action == Action.SAVE;
	}

	@Override
	public void save(boolean explicit) {
		project.save(explicit, outcomes -> {
			List<String> failures = failures(outcomes);
			if (failures.isEmpty()) {
				if (explicit) {
					listener.onSaved();
				}
			} else {
				listener.onFailed(TextUtils.join("\n", failures));
			}
			listener.onChanged();
		});
	}

	@NonNull
	private List<String> failures(
			@NonNull Map<ProjectPath, OpenProject.Outcome> outcomes) {
		List<String> failures = new ArrayList<>();
		for (Map.Entry<ProjectPath, OpenProject.Outcome> entry :
				outcomes.entrySet()) {
			String reason = reason(entry.getValue());
			if (reason != null) {
				failures.add(context.getString(R.string.file_not_saved,
						entry.getKey().toString(), reason));
			}
		}
		return failures;
	}

	// Conflicts and unreadable unsaved text have no screen of their own
	// yet; they are told like any failure.
	@Nullable
	private static String reason(@Nullable OpenProject.Outcome outcome) {
		if (outcome instanceof OpenProject.Conflict conflict) {
			return conflict.reason();
		} else if (outcome instanceof OpenProject.Failed failed) {
			return failed.reason();
		} else if (outcome instanceof OpenProject.Unreadable unreadable) {
			return unreadable.reason();
		}
		return null;
	}

	@Override
	public void saveCopy() {
	}

	@Override
	public void delete() {
	}

	@Override
	public void share() {
	}

	@Override
	public boolean isWallpaper() {
		return false;
	}

	@Override
	public void setAsWallpaper() {
	}
}
