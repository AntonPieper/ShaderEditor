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
 * and what was created is opened. Once it is written, it is opened
 * whatever became of the sheet: by the screen in front, also when that
 * is not the one that asked. Call it on the main thread.
 */
final class NewFile {
	/** What happens with what was created. */
	interface Created {
		/** A file was written; open it as a tap does. */
		void onFile(@NonNull String key, @NonNull ProjectPath path);

		/** A folder was made; show it. */
		void onFolder(@NonNull String key, @NonNull ProjectPath path);

		/** Tell message: what could not be made, or not be kept. */
		void onFailed(@NonNull String message);
	}

	// What was made: a file or a folder, and whether the library has
	// to learn of it as of a file made elsewhere.
	private record Made(@NonNull String key, @NonNull ProjectPath path,
			boolean folder, boolean library) {
	}

	// The one of the screen in front, and what was made, or why not,
	// while there was none.
	@Nullable
	private static NewFile current;
	@Nullable
	private static Made late;
	@Nullable
	private static String lateFailed;

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
	// The library's session, or null while it is not known.
	@Nullable
	private String library;
	private boolean destroyed;

	NewFile(@NonNull Context context, @NonNull ProjectHolds holds,
			@NonNull Executor listings, @NonNull Handler handler,
			@NonNull Created created) {
		this.context = context;
		this.holds = holds;
		this.listings = listings;
		this.handler = handler;
		this.created = created;
		current = this;
	}

	/**
	 * Hear what the screen before this one made, once this screen can
	 * show it.
	 */
	void resume() {
		String failed = lateFailed;
		Made made = late;
		lateFailed = null;
		late = null;
		if (failed != null) {
			created.onFailed(failed);
		}
		if (made != null) {
			show(made);
		}
	}

	/** The screen is gone. */
	void destroy() {
		destroyed = true;
		if (current == this) {
			current = null;
		}
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

	/** Takes the library's session, whose shaders the library makes. */
	void setLibrary(@NonNull String key) {
		library = key;
	}

	void create(@NonNull NewSheet.Kind kind, @NonNull String name) {
		OpenProject in = project;
		String at = key;
		ProjectParseResult<ProjectPath> parsed = ProjectPath.parse(
				folder != null ? folder + "/" + name : name);
		NewSheet asking = sheet;
		if (in == null || at == null || !parsed.isSuccess() ||
				asking == null) {
			return;
		}
		// One at a time: Create waits until this is made or failed.
		asking.setBusy(true);
		if (kind == NewSheet.Kind.SHADER && folder == null &&
				at.equals(library)) {
			newShader(asking, in, at, name);
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
			// The sheet may be gone, with its screen or without.
			boolean waiting = sheet == asking;
			if (change instanceof ProjectFiles.Refused refused) {
				failed(waiting ? asking : null, context.getString(
						R.string.nav_cannot_create, name, refused.reason()));
				return;
			}
			if (waiting) {
				asking.dismiss();
			}
			made(new Made(at, path, kind == NewSheet.Kind.FOLDER, false));
		});
	}

	// A shader in the library's root is copied from the one chosen as
	// the default for new shaders, else made from the built-in
	// template, with its quality and picture.
	// The template and its picture are read off the main thread.
	private void newShader(@NonNull NewSheet asking, @NonNull OpenProject in,
			@NonNull String at, @NonNull String name) {
		Context app = context.getApplicationContext();
		try {
			listings.execute(() -> {
				byte[] builtIn;
				try {
					builtIn = Resources.loadRawResource(app,
							R.raw.new_shader).getBytes(
							StandardCharsets.UTF_8);
				} catch (IOException e) {
					builtIn = new byte[0];
				}
				byte[] template = builtIn;
				// No picture until it runs: the list shows the file
				// icon for it, as for every file without one.
				handler.post(() -> newShader(asking, in, at, name,
						template, null));
			});
		} catch (RejectedExecutionException e) {
			// The drawer is gone.
		}
	}

	private void newShader(@NonNull NewSheet asking, @NonNull OpenProject in,
			@NonNull String at, @NonNull String name,
			@NonNull byte[] template, @Nullable byte[] picture) {
		if (sheet != asking) {
			// Not written yet: nothing is made.
			return;
		}
		holds.projects().newShader(name, context.getString(
				R.string.nav_untitled), template, picture, made -> {
			// The sheet may be gone, with its screen or without.
			boolean waiting = sheet == asking;
			if (made.path() == null) {
				failed(waiting ? asking : null, context.getString(
						R.string.nav_cannot_create, name, made.problem()));
				return;
			} else if (made.problem() != null) {
				// Its quality and picture once more; the file is there.
				holds.projects().dressNewShader(made.path(), template,
						picture, problem -> {
							if (problem != null) {
								failed(null, context.getString(
										R.string.library_not_kept, problem));
							}
						});
			}
			Made shader = new Made(at, made.path(), false, true);
			if (!waiting) {
				made(shader);
				return;
			}
			// Held until it has looked again: the sheet lets it go when
			// dismissed, and nothing else may hold the library, as when
			// an example is open.
			holds.hold(in);
			asking.dismiss();
			// The library learns of the file as of one made elsewhere.
			in.refresh(reread -> {
				if (destroyed) {
					made(shader);
				} else {
					created.onFile(at, shader.path());
				}
				holds.release(in);
			});
		});
	}

	// It was made: the screen in front shows it, or the next.
	private void made(@NonNull Made made) {
		NewFile front = destroyed ? current : this;
		if (front != null) {
			front.show(made);
		} else {
			late = made;
		}
	}

	private void show(@NonNull Made made) {
		if (made.folder()) {
			created.onFolder(made.key(), made.path());
			return;
		} else if (!made.library()) {
			created.onFile(made.key(), made.path());
			return;
		}
		// The library learns of the file as of one made elsewhere.
		holds.look(made.key(), opened -> {
			OpenProject looked = opened.project();
			if (looked == null) {
				failed(null, context.getString(R.string.nav_cannot_create,
						made.path().getFileName(), opened.problem()));
				return;
			}
			looked.refresh(reread -> {
				if (destroyed) {
					made(made);
				} else {
					created.onFile(made.key(), made.path());
				}
				holds.release(looked);
			});
		});
	}

	// Never dropped: the sheet says it while it waits; else the screen
	// in front does, or the next.
	private void failed(@Nullable NewSheet waiting, @NonNull String message) {
		NewFile front = destroyed ? current : this;
		if (waiting != null) {
			waiting.setBusy(false);
			waiting.fail(message);
		} else if (front != null) {
			front.created.onFailed(message);
		} else {
			lateFailed = message;
		}
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
		// The library keeps the names of the folders its shaders share.
		if (in == null && key != null && key.equals(library)) {
			names.add("textures");
			names.add("presets");
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
