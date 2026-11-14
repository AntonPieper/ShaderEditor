package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.project.Naming;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectParseResult;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.resource.Resources;
import de.markusfisch.android.shadereditor.widget.NewSheet;

/**
 * Creates a shader, an empty file or a folder in a folder opened
 * before, through the New sheet: the names in the folder are read off
 * the main thread, the file is written on the queue of the projects,
 * and what was created is opened. Call it on the main thread.
 */
final class NewFile {
	/** What happens with what was created. */
	interface Created {
		/** A file was written; open it as a tap does. */
		void onFile(@NonNull String key, @NonNull ProjectPath path);

		/** A folder was made; show it. */
		void onFolder(@NonNull String key, @NonNull ProjectPath path);
	}

	private final Context context;
	private final ProjectHolds holds;
	private final Executor listings;
	private final Handler handler;
	private final Created created;
	@Nullable
	private NewSheet sheet;
	@Nullable
	private OpenProject project;
	@Nullable
	private String key;
	@Nullable
	private ProjectPath folder;
	// Counts the folders asked for; an answer for one before is void.
	private int asked;

	NewFile(@NonNull Context context, @NonNull ProjectHolds holds,
			@NonNull Executor listings, @NonNull Handler handler,
			@NonNull Created created) {
		this.context = context;
		this.holds = holds;
		this.listings = listings;
		this.handler = handler;
		this.created = created;
	}

	/** Whether the sheet is open. */
	boolean isShowing() {
		return sheet != null;
	}

	/**
	 * Show the sheet for folder of the folder opened before with key,
	 * its root when null, where says where that is.
	 */
	void show(@NonNull NewSheet sheet, @NonNull String key,
			@Nullable ProjectPath folder, @NonNull String where) {
		this.sheet = sheet;
		sheet.showFolder(where);
		release();
		this.key = key;
		this.folder = folder;
		int ask = ++asked;
		holds.look(key, opened -> {
			OpenProject looked = opened.project();
			if (looked == null) {
				fail(ask, opened.problem());
				return;
			} else if (ask != asked || sheet == null) {
				holds.release(looked);
				return;
			}
			project = looked;
			readNames(ask, looked);
		});
	}

	void create(@NonNull NewSheet.Kind kind, @NonNull String name) {
		OpenProject in = project;
		String at = key;
		ProjectParseResult<ProjectPath> parsed = ProjectPath.parse(
				folder != null ? folder + "/" + name : name);
		if (in == null || at == null || !parsed.isSuccess()) {
			return;
		}
		ProjectPath path = parsed.value();
		Context app = context.getApplicationContext();
		holds.projects().change(at, files -> {
			if (kind == NewSheet.Kind.FOLDER) {
				return files.createFolder(path);
			}
			byte[] bytes;
			try {
				bytes = kind == NewSheet.Kind.SHADER
						? Resources.loadRawResource(app, R.raw.new_shader)
								.getBytes(StandardCharsets.UTF_8)
						: new byte[0];
			} catch (IOException e) {
				return new ProjectFiles.Refused(
						ProjectFiles.Refusal.FAILED, e.getMessage());
			}
			return files.write(path, bytes, null);
		}, change -> {
			NewSheet shown = sheet;
			if (shown == null || !at.equals(key)) {
				return;
			} else if (change instanceof ProjectFiles.Refused refused) {
				shown.fail(context.getString(R.string.nav_cannot_create,
						name, refused.reason()));
				return;
			}
			shown.dismiss();
			if (kind == NewSheet.Kind.FOLDER) {
				created.onFolder(at, path);
			} else {
				created.onFile(at, path);
			}
		});
	}

	/** The sheet is gone. */
	void dismissed() {
		sheet = null;
		++asked;
		release();
	}

	private void readNames(int ask, @NonNull OpenProject looked) {
		ProjectPath in = folder;
		try {
			listings.execute(() -> {
				OpenProject.Folder items = looked.list(in);
				boolean creates = looked.offers(in).create();
				handler.post(() -> named(ask, looked, in, items, creates));
			});
		} catch (RejectedExecutionException e) {
			// Closed.
		}
	}

	private void named(int ask, @NonNull OpenProject looked,
			@Nullable ProjectPath in, @NonNull OpenProject.Folder items,
			boolean creates) {
		if (ask != asked || sheet == null) {
			return;
		} else if (items.problem() != null) {
			sheet.fail(items.problem());
			return;
		} else if (!creates) {
			sheet.fail(context.getString(R.string.nav_cannot_change_folder));
			return;
		}
		List<String> names = new ArrayList<>();
		for (OpenProject.Item item : items.items()) {
			names.add(item.path().getFileName());
		}
		// Unsaved text of a file that is gone still has its name.
		String prefix = in != null ? in + "/" : "";
		for (ProjectPath path : looked.unsaved()) {
			String rest = path.toString();
			if (rest.startsWith(prefix) &&
					rest.indexOf('/', prefix.length()) < 0) {
				names.add(path.getFileName());
			}
		}
		sheet.setNames(new Naming(names));
	}

	private void fail(int ask, @Nullable String problem) {
		if (ask == asked && sheet != null) {
			sheet.fail(problem != null ? problem : "");
		}
	}

	private void release() {
		if (project != null) {
			holds.release(project);
			project = null;
		}
	}
}
