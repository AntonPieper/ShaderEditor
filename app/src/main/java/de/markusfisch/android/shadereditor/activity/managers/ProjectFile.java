package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.FileActions;
import de.markusfisch.android.shadereditor.navigation.FileLine;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.FileTexts.Callback;
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

		/**
		 * The file was changed for the first time, where it cannot be
		 * saved: offer to save a copy.
		 */
		void onReadOnly(@NonNull ProjectFile file);

		/** Save a copy of the file, with what it reads, elsewhere. */
		void onSaveCopy(@NonNull ProjectFile file);
	}

	/**
	 * What storage was found to hold in place of the file, as far as the
	 * user has to know: it changed or was deleted while the file has
	 * unsaved text here, it was deleted while it has none, or it cannot
	 * be read anymore; and the revision the user decides about, null when
	 * there is no file.
	 */
	public record Outside(@NonNull Kind kind, @Nullable String seen) {
		public enum Kind {
			CHANGED, DELETED, GONE, UNREADABLE,
			/** Its unsaved text could not be read when it was opened. */
			JOURNAL,
			/** Its unsaved text is what a reload replaced, come back. */
			REPLACED
		}
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
	// Whether the user was told that a change cannot be saved here.
	private boolean toldReadOnly;
	private boolean drawing;

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
		// "▶ file" only while that file is drawn: chosen is not running.
		ProjectPath running = drawing ? project.running() : null;
		return FileLine.of(folder, path.toString(),
				running != null ? running.toString() : null);
	}

	/**
	 * Takes whether a picture of what its project runs is being drawn,
	 * as the engine reports it.
	 */
	public void setDrawing(boolean drawing) {
		this.drawing = drawing;
	}

	@Override
	public boolean isModified() {
		return project.unsaved().contains(path);
	}

	@Override
	public void onTextModified() {
		listener.onChanged();
		if (isReadOnly() && !toldReadOnly && isModified()) {
			toldReadOnly = true;
			listener.onReadOnly(this);
		}
	}

	/** Return whether its folder can be read but not changed. */
	public boolean isReadOnly() {
		return project.storage().access() == OpenProject.Access.READ_ONLY;
	}

	/** Return what is to be said of the file's storage, or null. */
	@Nullable
	public Outside outside() {
		FileTexts.Found found = project.found(path);
		if (project.unreadable(path) != null) {
			return new Outside(Outside.Kind.JOURNAL, null);
		} else if (project.replaced(path) &&
				found != FileTexts.Found.MISSING) {
			return new Outside(Outside.Kind.REPLACED, null);
		} else if (project.conflict(path) != null) {
			return new Outside(found == FileTexts.Found.MISSING
					? Outside.Kind.DELETED
					: Outside.Kind.CHANGED, project.seen(path));
		} else if (found == FileTexts.Found.MISSING && !isModified()) {
			return new Outside(Outside.Kind.GONE, null);
		} else if (found == FileTexts.Found.UNAVAILABLE ||
				(found == FileTexts.Found.NOT_TEXT ||
						found == FileTexts.Found.TOO_LARGE) && !isModified()) {
			return new Outside(Outside.Kind.UNREADABLE, null);
		}
		return null;
	}

	/**
	 * Resolve the conflict as the user saw it in outside: reload the
	 * stored text, or keep the unsaved text so that the next explicit
	 * save overwrites the stored file. Nothing happens when storage was
	 * seen to change again since.
	 */
	public void resolve(@NonNull Outside outside,
			@NonNull OpenProject.Choice choice) {
		Callback<String> told = problem -> {
			if (problem != null) {
				listener.onFailed(context.getString(R.string.file_problem,
						path.toString(), problem));
			}
		};
		if (outside.kind() == Outside.Kind.REPLACED) {
			if (choice == OpenProject.Choice.RELOAD) {
				project.reload(path, told);
			} else {
				project.keepReplaced(path);
			}
			return;
		}
		project.resolve(path, choice, outside.seen(), told);
	}

	/** Give up unsaved text of the file that could not be read. */
	public void discard() {
		project.dismiss(path);
	}

	@Override
	public float getQuality() {
		return quality;
	}

	// Kept by the library for its files, through the activity.
	@Override
	public void setQuality(float quality) {
		this.quality = quality;
	}

	// Read-only files are kept with Save a copy instead of Save, and so
	// is unsaved text that cannot be saved where it is.
	@Override
	public boolean can(@NonNull Action action) {
		boolean readOnly = isReadOnly();
		return action == Action.SAVE && !readOnly ||
				action == Action.SAVE_COPY && (readOnly || atRisk()) ||
				action == Action.DELETE && !single && !readOnly ||
				action == Action.WALLPAPER && project.running() != null ||
				action == Action.RUN_THIS_FILE && FileActions.offersRun(
						path.equals(project.running()), project.runs(path));
	}

	// Whether its unsaved text cannot be saved where it is until the
	// user decides.
	private boolean atRisk() {
		Outside outside = outside();
		return outside != null && outside.kind() != Outside.Kind.GONE &&
				outside.kind() != Outside.Kind.JOURNAL && isModified();
	}

	// Run this file only when it can run and does not; the wallpaper
	// only when something runs, since it is what runs.
	@Override
	public boolean offers(@NonNull Action action) {
		return (action == Action.SAVE || action == Action.SAVE_COPY ||
				action == Action.DELETE || action == Action.WALLPAPER ||
				action == Action.RUN_THIS_FILE) && can(action);
	}

	// Unsaved text of a read-only file stays where it is, journaled,
	// until it is copied or reloaded.
	@Override
	public void save(boolean explicit) {
		if (isReadOnly()) {
			if (explicit && isModified()) {
				listener.onReadOnly(this);
			}
			return;
		}
		project.save(explicit, outcomes -> {
			List<String> failures = failures(outcomes, explicit);
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

	// A file changed outside is said by its bar and its mark in the
	// drawer; a save the user did not ask for does not say it again.
	@NonNull
	private List<String> failures(
			@NonNull Map<ProjectPath, OpenProject.Outcome> outcomes,
			boolean explicit) {
		List<String> failures = new ArrayList<>();
		for (Map.Entry<ProjectPath, OpenProject.Outcome> entry :
				outcomes.entrySet()) {
			if (!explicit &&
					entry.getValue() instanceof OpenProject.Conflict) {
				continue;
			}
			String reason = reason(entry.getValue());
			if (reason != null) {
				failures.add(context.getString(R.string.file_not_saved,
						entry.getKey().toString(), reason));
			}
		}
		return failures;
	}

	// A save that is refused answers the tap like any failure; for the
	// file in the editor the bar above the code says what can be done.
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
		if (can(Action.SAVE_COPY)) {
			listener.onSaveCopy(this);
		}
	}

	// With the project, which asks before unsaved text goes; the
	// editor closes once the project says it is deleted.
	@Override
	public void delete() {
		if (!can(Action.DELETE)) {
			return;
		}
		Deleting.delete(context, project, path, moved -> {
			if (moved instanceof OpenProject.Refused refused) {
				listener.onFailed(context.getString(
						R.string.nav_cannot_delete, path.getFileName(),
						refused.reason()));
			}
		});
	}

	@Override
	public void share() {
	}

	// The library knows what the wallpaper shows; the activity asks it.
	@Override
	public boolean isWallpaper() {
		return false;
	}

	// The activity installs what runs, through the library.
	@Override
	public void setAsWallpaper() {
	}

	@Override
	public void runThisFile() {
		if (can(Action.RUN_THIS_FILE)) {
			project.runThisFile(path);
		}
	}
}
