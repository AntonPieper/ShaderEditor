package de.markusfisch.android.shadereditor.activity.managers;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.fragment.FileLinks;
import de.markusfisch.android.shadereditor.project.HostCompletion;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The files around the file of a folder in the editor. A folder is
 * listed off the main thread the first time a path in it is
 * completed, and kept; it is listed again, while the old listing
 * serves, each time the caret enters a path, so that files made since
 * are seen. A rename or deletion of the project, or another file
 * shown, drops the listings. Call it on the main thread.
 */
final class ProjectLinks implements FileLinks {
	/** Whether the picture's context draws into rgba16f targets. */
	interface HalfFloat {
		boolean drawn();
	}

	private final ExecutorService listings =
			Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	// Listed folders by path, the root as "".
	private final Map<String, List<HostCompletion.Entry>> listed =
			new HashMap<>();
	private final Set<String> listing = new HashSet<>();
	// The folders listed since the caret last entered a path.
	private final Set<String> fresh = new HashSet<>();
	@NonNull
	private final HalfFloat halfFloat;
	@Nullable
	private OpenProject project;
	@Nullable
	private ProjectPath file;
	@Nullable
	private Runnable changed;
	// The number of the last rename or deletion heard of.
	private int heard;
	// Counts the forgettings; a listing made before one is void.
	private int generation;

	ProjectLinks(@NonNull HalfFloat halfFloat) {
		this.halfFloat = halfFloat;
	}

	/** file of project is in the editor now. */
	void show(@NonNull OpenProject project, @NonNull ProjectPath file) {
		forget();
		this.project = project;
		this.file = file;
		heard = last(project.events(0));
	}

	/** The project told of a change: a move makes the listings old. */
	void changed() {
		OpenProject open = project;
		if (open == null) {
			return;
		}
		List<OpenProject.Event> events = open.events(heard);
		if (!events.isEmpty()) {
			heard = last(events);
			forget();
		}
	}

	private void forget() {
		++generation;
		listed.clear();
		listing.clear();
		fresh.clear();
	}

	void destroy() {
		forget();
		project = null;
		listings.shutdownNow();
	}

	@NonNull
	@Override
	public ProjectPath file() {
		if (file == null) {
			throw new IllegalStateException("No file shown");
		}
		return file;
	}

	@Override
	public boolean drawsHalfFloat() {
		return halfFloat.drawn();
	}

	@Override
	public void refresh() {
		fresh.clear();
	}

	@Override
	public void setListener(@Nullable Runnable changed) {
		this.changed = changed;
	}

	@Nullable
	@Override
	public List<HostCompletion.Entry> list(@Nullable ProjectPath folder) {
		String key = folder != null ? folder.toString() : "";
		List<HostCompletion.Entry> entries = listed.get(key);
		OpenProject open = project;
		// A listing older than the caret's entering a path is used while
		// it is made again.
		if ((entries != null && fresh.contains(key)) || open == null ||
				!listing.add(key)) {
			return entries;
		}
		int asked = generation;
		listings.execute(() -> {
			List<HostCompletion.Entry> read = new ArrayList<>();
			for (OpenProject.Item item : open.list(folder).items()) {
				read.add(new HostCompletion.Entry(
						item.path().getFileName(), item.folder()));
			}
			handler.post(() -> {
				if (asked != generation) {
					return;
				}
				listing.remove(key);
				fresh.add(key);
				if (!read.equals(listed.put(key, read)) && changed != null) {
					changed.run();
				}
			});
		});
		return entries;
	}

	private static int last(@NonNull List<OpenProject.Event> events) {
		return events.isEmpty()
				? 0
				: events.get(events.size() - 1).number();
	}
}
