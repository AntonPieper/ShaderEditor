package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.fragment.FileLinks;
import de.markusfisch.android.shadereditor.graphics.OldPlaceholder;
import de.markusfisch.android.shadereditor.navigation.Face;
import de.markusfisch.android.shadereditor.project.FileKind;
import de.markusfisch.android.shadereditor.project.HostCompletion;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Previews;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.Projects;
import de.markusfisch.android.shadereditor.resource.Resources;

/**
 * The files around the file of a folder in the editor. A folder is
 * listed off the main thread the first time a path in it is
 * completed, and kept; it is listed again, while the old listing
 * serves, each time the caret enters a path, so that files made since
 * are seen. A rename or deletion of the project, or another file
 * shown, drops the listings. The file the caret rests on goes to the strip with its
 * picture, read off the main thread. Call it on the main thread.
 */
final class ProjectLinks implements FileLinks {
	/** What the links show and do on the screen. */
	interface Host {
		/** Whether the picture's context draws into rgba16f targets. */
		boolean drawsHalfFloat();

		/** Show reference in the strip, the completions when null. */
		void showReference(@Nullable ExtraKeysManager.Reference reference);

		/**
		 * Open path of the project in the editor at line, from 1, or
		 * where its caret is when 0.
		 */
		void open(@NonNull ProjectPath path, int line);

		/**
		 * Show the key back to the file named name at line, from 1,
		 * doing back; none when back is null.
		 */
		void showBack(@NonNull String name, int line,
				@Nullable Runnable back);

		/**
		 * A file was made in the project: build what runs again, and
		 * list the file where the files are listed.
		 */
		void made();

		/** Tell message, about what failed. */
		void failed(@NonNull String message);
	}

	// Where Open went from, and to.
	private record Back(@NonNull OpenProject project,
			@NonNull ProjectPath from, int line, @NonNull ProjectPath to) {
	}

	/**
	 * Shows a pass of the running shader full size in place of its
	 * picture: the engine's, once it can. Without one, Show is not
	 * offered.
	 */
	interface PassShow {
		void show(@NonNull ProjectPath pass);
	}

	// The pictures kept, by path: a few lines' worth.
	private static final int KEPT = 16;

	private final ExecutorService listings =
			Executors.newSingleThreadExecutor();
	private final Handler handler = new Handler(Looper.getMainLooper());
	// Listed folders by path, the root as "".
	private final Map<String, List<HostCompletion.Entry>> listed =
			new HashMap<>();
	private final Set<String> listing = new HashSet<>();
	// The folders listed since the caret last entered a path.
	private final Set<String> fresh = new HashSet<>();
	private final LruCache<ProjectPath, Bitmap> pictures =
			new LruCache<>(KEPT);
	private final Set<ProjectPath> unpictured = new HashSet<>();
	@NonNull
	private final Context context;
	@NonNull
	private final Projects projects;
	@NonNull
	private final Host host;
	// The size of a picture read, in pixels.
	private final int size;
	@Nullable
	private PassShow passShow;
	@Nullable
	private OpenProject project;
	@Nullable
	private ProjectPath file;
	@Nullable
	private Runnable changed;
	@Nullable
	private HostCompletion.Named pointed;
	// The way back after Open, until it is taken or another file is
	// shown; or null.
	@Nullable
	private Back back;
	// The number of the last rename or deletion heard of.
	private int heard;
	// Counts the forgettings; a listing made before one is void.
	private int generation;

	ProjectLinks(@NonNull Context context, @NonNull Projects projects,
			@NonNull Host host) {
		this.context = context.getApplicationContext();
		this.projects = projects;
		this.host = host;
		size = Math.round(64 * context.getResources()
				.getDisplayMetrics().density);
	}

	/** Show passes with show; none, and Show is not offered, when null. */
	void setPassShow(@Nullable PassShow show) {
		passShow = show;
	}

	/**
	 * file of project is in the editor now. After Open, the key back
	 * shows in the file opened; any other file takes it away.
	 */
	void show(@NonNull OpenProject project, @NonNull ProjectPath file) {
		if (project == this.project && file.equals(this.file)) {
			return;
		}
		forget();
		this.project = project;
		this.file = file;
		heard = last(project.events(0));
		Back way = back;
		if (way != null && way.project() == project &&
				way.to().equals(file)) {
			host.showBack(way.from().getFileName(), way.line(), () -> {
				back = null;
				host.open(way.from(), way.line());
			});
		} else {
			back = null;
			host.showBack("", 0, null);
		}
	}

	/** No file is in the editor. */
	void hide() {
		forget();
		project = null;
		file = null;
		back = null;
		host.showBack("", 0, null);
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
		pictures.evictAll();
		unpictured.clear();
		point(null);
	}

	void destroy() {
		hide();
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
		return host.drawsHalfFloat();
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

	@Override
	public void point(@Nullable HostCompletion.Named named) {
		if (Objects.equals(named, pointed)) {
			return;
		}
		pointed = named;
		OpenProject open = project;
		if (named == null || open == null) {
			host.showReference(null);
			return;
		}
		ProjectPath path = named.path();
		boolean image = named.kind() != HostCompletion.Kind.INCLUDE &&
				FileKind.hint(path.getFileName(), null) == FileKind.PICTURE;
		host.showReference(reference(named, image));
		if (named.kind() == HostCompletion.Kind.INCLUDE ||
				pictures.get(path) != null || unpictured.contains(path)) {
			return;
		}
		int asked = generation;
		listings.execute(() -> {
			Bitmap picture = image
					? image(open.reading(), path)
					: preview(open.id(), path);
			handler.post(() -> {
				if (asked != generation) {
					return;
				}
				if (picture != null) {
					pictures.put(path, picture);
				} else {
					unpictured.add(path);
				}
				if (named.equals(pointed)) {
					host.showReference(reference(named, image));
				}
			});
		});
	}

	@NonNull
	private ExtraKeysManager.Reference reference(
			@NonNull HostCompletion.Named named, boolean image) {
		ProjectPath path = named.path();
		PassShow show = passShow;
		boolean code = named.kind() == HostCompletion.Kind.INCLUDE;
		return new ExtraKeysManager.Reference(path.getFileName(),
				pictures.get(path),
				code
						? ExtraKeysManager.Reference.Kind.CODE
						: image
						? ExtraKeysManager.Reference.Kind.IMAGE
						: ExtraKeysManager.Reference.Kind.PASS,
				show != null && !code && !image
						? () -> show.show(path)
						: null,
				image ? null : () -> open(path, 0));
	}

	@Override
	public void create(@NonNull ProjectPath path, boolean include) {
		OpenProject open = project;
		if (open == null) {
			return;
		}
		projects.change(open.id(), files -> {
			byte[] bytes;
			try {
				bytes = (include
						? guard(path.getFileName())
						: Resources.loadRawResource(context,
						R.raw.new_shader))
						.getBytes(StandardCharsets.UTF_8);
			} catch (IOException e) {
				return new ProjectFiles.Refused(ProjectFiles.Refusal.FAILED,
						e.getMessage());
			}
			return files.write(path, bytes, null);
		}, change -> {
			if (change instanceof ProjectFiles.Refused refused) {
				host.failed(context.getString(R.string.nav_cannot_create,
						path.getFileName(), refused.reason()));
			} else if (open == project) {
				forget();
				open.refresh(read -> host.made());
			}
		});
	}

	// An empty include named name that is read once however often it is
	// included.
	@NonNull
	static String guard(@NonNull String name) {
		StringBuilder macro = new StringBuilder();
		for (char c : name.toUpperCase(Locale.ROOT).toCharArray()) {
			macro.append(c < 128 && Character.isLetterOrDigit(c) ? c : '_');
		}
		if (Character.isDigit(macro.charAt(0))) {
			macro.insert(0, '_');
		}
		return "#ifndef " + macro + "\n#define " + macro + "\n\n#endif\n";
	}

	@Override
	public void open(@NonNull ProjectPath path, int line) {
		OpenProject open = project;
		ProjectPath from = file;
		Document document = open != null && from != null
				? open.document(from)
				: null;
		if (document == null) {
			return;
		} else if (!path.equals(from)) {
			Text text = document.text();
			back = new Back(open, from,
					text.lineOf(document.selection().head()) + 1, path);
		}
		host.open(path, line);
	}

	// The picture of the pass as it ran last, or null. Off the main
	// thread.
	@Nullable
	private Bitmap preview(@NonNull String id, @NonNull ProjectPath path) {
		Previews previews = projects.previews(id);
		Previews.Picture picture = previews != null
				? previews.get(path)
				: null;
		byte[] png = picture != null
				? OldPlaceholder.real(context, picture.png())
				: null;
		return png != null
				? BitmapFactory.decodeByteArray(png, 0, png.length)
				: null;
	}

	// The image at path, read about size pixels small, or null. Off
	// the main thread.
	@Nullable
	private Bitmap image(@NonNull ProjectFiles files,
			@NonNull ProjectPath path) {
		ProjectFiles.Read read = files.read(path, Face.LARGEST);
		if (!(read instanceof ProjectFiles.Content content)) {
			return null;
		}
		byte[] bytes = content.bytes();
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
		int sample = 1;
		while (Math.min(options.outWidth, options.outHeight) / sample / 2 >=
				size) {
			sample *= 2;
		}
		options.inJustDecodeBounds = false;
		options.inSampleSize = sample;
		return BitmapFactory.decodeByteArray(bytes, 0, bytes.length,
				options);
	}

	private static int last(@NonNull List<OpenProject.Event> events) {
		return events.isEmpty()
				? 0
				: events.get(events.size() - 1).number();
	}
}
