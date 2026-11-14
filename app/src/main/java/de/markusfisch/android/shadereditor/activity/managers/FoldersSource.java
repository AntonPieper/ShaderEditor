package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.adapter.Samples;
import de.markusfisch.android.shadereditor.navigation.Level;
import de.markusfisch.android.shadereditor.navigation.Listing;
import de.markusfisch.android.shadereditor.navigation.Order;
import de.markusfisch.android.shadereditor.navigation.Recent;
import de.markusfisch.android.shadereditor.navigation.Row;
import de.markusfisch.android.shadereditor.navigation.Source;
import de.markusfisch.android.shadereditor.project.ProjectSessions;

/**
 * The top level: the library, the examples, and the folders and files
 * opened before.
 */
final class FoldersSource implements Source {
	private final Context context;
	private final Source library;
	private List<Recent> recent = List.of();

	FoldersSource(@NonNull Context context, @NonNull Source library) {
		this.context = context;
		this.library = library;
	}

	/** Takes the recent projects, the one opened last first. */
	void setRecent(@NonNull List<ProjectSessions.Recent> sessions) {
		List<Recent> recent = new ArrayList<>();
		for (ProjectSessions.Recent session : sessions) {
			ProjectSessions.Kind kind = session.locator().kind();
			if (kind != ProjectSessions.Kind.DATABASE) {
				recent.add(new Recent(session.id(),
						kind == ProjectSessions.Kind.DOCUMENT,
						session.name(), session.location(),
						session.opened(), session.unsaved()));
			}
		}
		this.recent = recent;
	}

	@Override
	public Listing list(Order order) {
		Listing shaders = library.list(order);
		List<Listing.Section> sections = new ArrayList<>();
		sections.add(new Listing.Section(null, List.of(
				folder(Level.LIBRARY, Row.Picture.Icon.LIBRARY,
						shaders.title(), shaders.subtitle(),
						EnumSet.noneOf(Row.Mark.class)),
				folder(Level.EXAMPLES, Row.Picture.Icon.EXAMPLES,
						context.getString(R.string.load_sample),
						context.getString(R.string.nav_examples_count,
								Samples.ALL.size()),
						EnumSet.of(Row.Mark.READ_ONLY)))));
		if (!recent.isEmpty()) {
			sections.add(new Listing.Section(
					context.getString(R.string.nav_recent),
					Recent.rows(recent,
							context.getString(R.string.nav_single_file))));
		}
		return new Listing(context.getString(R.string.nav_folders),
				null,
				false,
				false,
				Listing.Bar.OPEN,
				sections);
	}

	@Override
	public void open(Row row, boolean run) {
	}

	@Override
	public void act(Row row, Row.Action action) {
	}

	@NonNull
	private static Row folder(@NonNull Level level,
			@NonNull Row.Picture.Icon icon,
			@NonNull String name,
			String detail,
			@NonNull Set<Row.Mark> marks) {
		return new Row(level.name(), Row.Kind.FOLDER, Row.Picture.of(icon),
				name, detail, 0, level, marks,
				EnumSet.noneOf(Row.Action.class));
	}
}
