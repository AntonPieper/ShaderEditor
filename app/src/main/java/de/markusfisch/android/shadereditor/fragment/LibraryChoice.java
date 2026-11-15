package de.markusfisch.android.shadereditor.fragment;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.activity.managers.LibraryLooks;
import de.markusfisch.android.shadereditor.adapter.ShaderAdapter;
import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.database.DataRecords;
import de.markusfisch.android.shadereditor.graphics.OldPlaceholder;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.preference.Preferences;
import de.markusfisch.android.shadereditor.project.Library;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.project.WallpaperSnapshots;
import de.markusfisch.android.shadereditor.project.Previews;

/**
 * Where the shaders are files, the settings choose the wallpaper and
 * the shader new ones are copied from among the files of the library's
 * root, as they are saved. Nothing it hears after its screen is gone
 * does anything.
 */
final class LibraryChoice {
	private static final Executor READING =
			Executors.newSingleThreadExecutor();
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	// Made off the main thread: making it reads the disk.
	private static Projects projects;

	private record Choice(List<ProjectPath> paths,
			List<DataRecords.ShaderInfo> rows, int chosen) {
	}

	private LibraryChoice() {
	}

	/** Say which file preference, of key, has chosen. */
	static void summarize(@NonNull Fragment fragment,
			@NonNull Preference preference) {
		Context context = fragment.requireContext().getApplicationContext();
		boolean wallpaper = isWallpaper(preference.getKey());
		long id = ShaderEditorApp.preferences.getWallpaperShader();
		READING.execute(() -> {
			Projects projects = projects(context);
			ProjectPath chosen = chosen(projects, wallpaper, id);
			// A file of another folder is the wallpaper's, not one of
			// the library: named with that folder.
			WallpaperSnapshots.Snapshot installed = wallpaper && chosen == null
					? projects.wallpaper().current()
					: null;
			MAIN.post(() -> {
				if (!fragment.isAdded()) {
					return;
				}
				if (chosen != null) {
					preference.setSummary(chosen.getFileName());
				} else if (installed != null) {
					String file = installed.entry().getFileName();
					preference.setSummary(installed.source() != null
							? fragment.getString(R.string.file_in_folder,
									file, installed.source())
							: file);
				} else {
					preference.setSummary(fragment.getString(
							R.string.no_shader_selected));
				}
			});
		});
	}

	/** Let the user choose for the preference of key. */
	static void show(@NonNull Fragment fragment, @NonNull String key,
			@NonNull Runnable changed) {
		Context context = fragment.requireContext().getApplicationContext();
		boolean wallpaper = isWallpaper(key);
		long id = ShaderEditorApp.preferences.getWallpaperShader();
		String none = context.getString(R.string.no_shader_selected);
		READING.execute(() -> {
			Projects projects = projects(context);
			Choice choice = read(context, projects, wallpaper, id, none);
			MAIN.post(() -> {
				if (fragment.isAdded()) {
					show(fragment, projects, wallpaper, choice, changed);
				}
			});
		});
	}

	private static void show(@NonNull Fragment fragment,
			@NonNull Projects projects, boolean wallpaper,
			@NonNull Choice choice, @NonNull Runnable changed) {
		Context context = fragment.requireContext();
		ShaderAdapter adapter = new ShaderAdapter(context,
				R.layout.row_shader_choice);
		// A file without a picture shows the file icon, as in the drawer.
		adapter.setPlaceholder(R.drawable.ic_draft);
		adapter.setData(choice.rows());
		adapter.setSelectedId(choice.chosen() >= 0
				? choice.rows().get(choice.chosen()).id()
				: -1);
		new MaterialAlertDialogBuilder(context)
				.setTitle(wallpaper
						? R.string.wallpaper
						: R.string.default_new_shader)
				.setSingleChoiceItems(adapter, choice.chosen(),
						(dialog, which) -> {
							dialog.dismiss();
							ProjectPath path = choice.paths().get(which);
							if (wallpaper) {
								install(fragment, projects, path, changed);
							} else {
								projects.setTemplate(path, problem ->
										told(fragment, problem,
												R.string.library_not_kept,
												changed));
							}
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// Build the file as it is saved and have the wallpaper show it.
	private static void install(@NonNull Fragment fragment,
			@NonNull Projects projects, @Nullable ProjectPath path,
			@NonNull Runnable changed) {
		if (path == null) {
			return;
		}
		Library library = projects.library();
		READING.execute(() -> {
			Preparation preparation = library.prepareSaved(path,
					UniformTable.builtins());
			float quality = library.settings().quality(path);
			int limit = library.settings().frameRateLimit(path);
			MAIN.post(() -> projects.setWallpaper(preparation, path,
					quality, limit, path, null, problem -> {
						if (problem == null) {
							LibraryLooks.showsInstalled();
						}
						told(fragment, problem,
								R.string.cannot_install_wallpaper, changed);
					}));
		});
	}

	private static void told(@NonNull Fragment fragment,
			@Nullable String problem, int message,
			@NonNull Runnable changed) {
		Activity activity = fragment.getActivity();
		if (!fragment.isAdded() || activity == null) {
			return;
		}
		if (problem != null) {
			de.markusfisch.android.shadereditor.widget.Message.show(activity,
					fragment.getString(message, problem));
		}
		changed.run();
	}

	// On READING.
	@NonNull
	private static Projects projects(@NonNull Context context) {
		if (projects == null) {
			projects = new Projects(context);
		}
		return projects;
	}

	private static boolean isWallpaper(@Nullable String key) {
		return Preferences.WALLPAPER_SHADER.equals(key);
	}

	// Off the main thread.
	@Nullable
	private static ProjectPath chosen(@NonNull Projects projects,
			boolean wallpaper, long id) {
		Library library = projects.library();
		return wallpaper
				? library.wallpaperFile(projects.wallpaper().current(), id)
				: library.settings().template();
	}

	// The files of the root, last changed first; the choice of none
	// first where none can be chosen. Off the main thread.
	@NonNull
	private static Choice read(@NonNull Context context,
			@NonNull Projects projects, boolean wallpaper,
			long id, @NonNull String none) {
		Library library = projects.library();
		ProjectPath chosen = chosen(projects, wallpaper, id);
		List<ProjectPath> paths = new ArrayList<>();
		List<DataRecords.ShaderInfo> rows = new ArrayList<>();
		if (!wallpaper) {
			paths.add(null);
			rows.add(new DataRecords.ShaderInfo(0, none, null, null));
		}
		int at = wallpaper ? -1 : 0;
		Previews previews = library.previews();
		for (Library.RootFile file : library.rootFiles()) {
			ProjectPath path = ProjectPath.parse(file.name()).value();
			if (path == null) {
				continue;
			}
			if (path.equals(chosen)) {
				at = paths.size();
			}
			Previews.Picture picture = previews.get(path);
			rows.add(new DataRecords.ShaderInfo(paths.size(),
					file.name(), null, picture != null
							? OldPlaceholder.real(context, picture.png())
							: null));
			paths.add(path);
		}
		return new Choice(paths, rows, at);
	}
}
