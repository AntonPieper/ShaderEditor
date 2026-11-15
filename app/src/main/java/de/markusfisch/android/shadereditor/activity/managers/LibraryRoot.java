package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.database.DataSource;
import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.graphics.OldPlaceholder;
import de.markusfisch.android.shadereditor.navigation.Face;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Notice;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.project.LibraryMove;
import de.markusfisch.android.shadereditor.project.ShaderLanguage;

/**
 * What the library's root lists besides its files, which its source
 * adds and no other folder has: the notice of a library without a
 * shader (NAVIGATION 6.7), and the shaders of the database that could
 * not be moved, after the files, where a tap opens them. Call it on
 * the main thread.
 */
final class LibraryRoot implements FolderSource.Decoration {
	// The key of a row of a shader that was not moved; no file name
	// holds the character it starts with.
	private static final String NOT_MOVED = "\u0000not moved ";

	private final Context context;
	private final Executor executor;
	private final Handler handler;
	private final Runnable changed;
	@NonNull
	private List<LibraryMove.Failed> notMoved = Collections.emptyList();
	@NonNull
	private Map<Long, byte[]> pictures = Collections.emptyMap();

	/** @param changed called when what it adds changed */
	LibraryRoot(@NonNull Context context, @NonNull Executor executor,
			@NonNull Handler handler, @NonNull Runnable changed) {
		this.context = context;
		this.executor = executor;
		this.handler = handler;
		this.changed = changed;
	}

	/** The key of the row of the shader of the database with id. */
	@NonNull
	static String key(long id) {
		return NOT_MOVED + id;
	}

	/** The id of the shader of the database a row's key stands for, or -1. */
	static long shader(@Nullable String key) {
		return key != null && key.startsWith(NOT_MOVED)
				? Long.parseLong(key.substring(NOT_MOVED.length()))
				: -1;
	}

	/**
	 * Takes the shaders of the database that could not be moved, each
	 * with its database thumbnail (NAVIGATION 11.2), read off the main
	 * thread; the database's grey square is none.
	 */
	void setNotMoved(@NonNull List<LibraryMove.Failed> failed) {
		notMoved = failed;
		changed.run();
		Context app = context.getApplicationContext();
		try {
			executor.execute(() -> {
				Map<Long, byte[]> read = new HashMap<>();
				DataSource database = Database.getInstance(app)
						.getDataSource();
				for (LibraryMove.Failed shader : failed) {
					byte[] png = OldPlaceholder.real(app,
							database.shader.getThumbnail(shader.id()));
					if (png != null) {
						read.put(shader.id(), png);
					}
				}
				handler.post(() -> {
					if (notMoved == failed) {
						pictures = read;
						changed.run();
					}
				});
			});
		} catch (RejectedExecutionException e) {
			// Destroyed.
		}
	}

	void clear() {
		notMoved = Collections.emptyList();
		pictures = Collections.emptyMap();
	}

	@NonNull
	@Override
	public Listing decorate(@NonNull Level level, @NonNull Listing listing) {
		return level.path() == null
				? withNotMoved(withoutShaders(listing))
				: listing;
	}

	// A library without a shader says so and offers to make one; its
	// folders, such as textures, may be there (NAVIGATION 6.7).
	@NonNull
	private Listing withoutShaders(@NonNull Listing listing) {
		Notice notice = listing.notice();
		if (!notMoved.isEmpty() || (notice != null &&
				notice.kind() != Notice.Kind.EMPTY)) {
			return listing;
		}
		for (Listing.Section section : listing.sections()) {
			for (Row row : section.rows()) {
				if (row.kind() != Row.Kind.FOLDER) {
					return listing;
				}
			}
		}
		return new Listing(listing.title(), listing.subtitle(),
				listing.readOnly(), listing.sortable(), listing.bar(),
				listing.sections(), new Notice(Notice.Kind.NO_SHADERS, null));
	}

	// The shaders that could not be moved, by title and why, after
	// the files.
	@NonNull
	private Listing withNotMoved(@NonNull Listing listing) {
		if (notMoved.isEmpty()) {
			return listing;
		}
		List<Row> rows = new ArrayList<>();
		for (LibraryMove.Failed failed : notMoved) {
			String title = failed.title();
			rows.add(new Row(key(failed.id()), Row.Kind.SHADER,
					// A shader of the database, named as a shader is.
					Face.of(Face.Of.FILE, ShaderLanguage.PASS,
							pictures.get(failed.id()), true),
					title != null && !title.isEmpty()
							? title
							: context.getString(R.string.nav_untitled),
					failed.reason(), 0, null, Collections.emptySet(),
					Collections.emptySet()));
		}
		List<Listing.Section> sections = new ArrayList<>(listing.sections());
		sections.add(new Listing.Section(context.getString(
				R.string.library_not_moved_section, rows.size()), rows));
		return new Listing(listing.title(), listing.subtitle(),
				listing.readOnly(), listing.sortable(), listing.bar(),
				sections, listing.notice());
	}
}
