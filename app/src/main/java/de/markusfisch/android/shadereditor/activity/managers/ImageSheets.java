package de.markusfisch.android.shadereditor.activity.managers;

import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.editor.InsertTexture;
import de.markusfisch.android.shadereditor.navigation.Item;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.widget.ImageSheet;

/**
 * The sheet of an image of a folder: what it looks like, whether the
 * shader that runs now uses it (only that one: which other shaders
 * read it is not known without reading them all), and what can be
 * done with it. Insert as texture is offered only where
 * {@link InsertTexture#offer} says so, and Rename and Delete where its
 * folder can be changed; else one sentence says why. Call it on the
 * main thread.
 */
final class ImageSheets {
	/** What the editor has, as far as an image sheet asks. */
	interface Editor {
		/**
		 * The file in the editor when it is of the folder or single file
		 * opened before with key, else null.
		 */
		@Nullable
		InsertTexture.Target edited(@NonNull String key);

		/**
		 * Add statement to the text in the editor as one step to undo,
		 * and put the caret on the sampler name it declares.
		 */
		void insertTexture(@NonNull String statement, @NonNull String name);
	}

	/** Reads the image for its sheet, until the sheet goes. */
	interface Reader {
		void large(@NonNull OpenProject project, @NonNull ProjectPath path,
				@NonNull ImageSheet sheet);
	}

	private final AppCompatActivity activity;
	private final Editor editor;
	private final FolderActions actions;
	private final Runnable inserted;
	@Nullable
	private ImageSheet sheet;

	/** @param inserted called after the lines were added: closes. */
	ImageSheets(@NonNull AppCompatActivity activity, @NonNull Editor editor,
			@NonNull FolderActions actions, @NonNull Runnable inserted) {
		this.activity = activity;
		this.editor = editor;
		this.actions = actions;
		this.inserted = inserted;
	}

	/**
	 * Show the image of row, a file of source whose key is its path
	 * from the root, listed in level, where running runs and uses used.
	 *
	 * @param reader reads the image for the sheet
	 */
	void show(@NonNull FolderSource source, @NonNull Level level,
			@NonNull Row row, @Nullable Item.Place running,
			@NonNull Set<Item.Place> used, @NonNull Reader reader) {
		OpenProject project = source.project();
		var parsed = ProjectPath.parse(row.key());
		if (project == null || !parsed.isSuccess()) {
			return;
		}
		ProjectPath path = parsed.value();
		String key = source.key();
		// The file, wherever it is listed: in its folder or under
		// Running, which shows files of other folders.
		Item.Place place = Item.Place.file(key, row.key());
		String where = source.title(place.level());
		if (running != null && (used.contains(place) ||
				place.equals(running))) {
			String runs = running.key();
			where += " · " + activity.getString(R.string.nav_used_by,
					runs.substring(runs.lastIndexOf('/') + 1));
		}
		InsertTexture.Target edited = editor.edited(key);
		InsertTexture.Offer offer = InsertTexture.offer(edited, path);
		InsertTexture.Lines lines = offer == InsertTexture.Offer.OFFERED
				? InsertTexture.of(edited.text(), edited.path().toString(),
						path.toString())
				: null;
		String says = switch (offer) {
			case OFFERED -> lines.statement();
			case NO_SHADER_HERE -> activity.getString(
					R.string.nav_insert_where);
			case NOT_A_SHADER -> activity.getString(
					R.string.nav_insert_not_shader,
					edited.path().getFileName());
			case READ_ONLY -> activity.getString(
					R.string.nav_insert_read_only,
					edited.path().getFileName());
			case READS_ALREADY -> activity.getString(
					R.string.nav_insert_reads_already,
					edited.path().getFileName());
		};
		// Where Rename and Delete are not offered, why: its folder
		// cannot be changed, or was not read yet, as for a file under
		// Running.
		String noChanges = row.actions().contains(Row.Action.RENAME)
				? null
				: source.isReadOnly()
				? activity.getString(R.string.nav_image_read_only)
				: activity.getString(R.string.nav_image_open_folder,
						source.title(place.level()));
		dismiss();
		ImageSheet shown = new ImageSheet(activity, row.name(), where,
				lines != null ? edited.path().getFileName() : null, says,
				noChanges, new ImageSheet.Listener() {
					@Override
					public void onInsert() {
						if (lines != null) {
							editor.insertTexture(lines.statement(),
									lines.name());
							inserted.run();
						}
					}

					@Override
					public void onRename() {
						actions.act(source, level, row, Row.Action.RENAME);
					}

					@Override
					public void onDelete() {
						actions.act(source, level, row, Row.Action.DELETE);
					}
				});
		sheet = shown;
		shown.show();
		reader.large(project, path, shown);
	}

	/** The image's info line, as the sheet shows it. */
	@NonNull
	String info(@NonNull ImageFaces.Large large) {
		return activity.getString(R.string.nav_image_info, large.width(),
				large.height(), large.format(),
				Formatter.formatShortFileSize(activity, large.bytes()));
	}

	void dismiss() {
		if (sheet != null) {
			sheet.dismiss();
			sheet = null;
		}
	}
}
