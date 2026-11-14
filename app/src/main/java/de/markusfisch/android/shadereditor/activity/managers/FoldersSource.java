package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
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
	interface Remover {
		/** Asks whether to remove what was opened before from Recent. */
		void remove(@NonNull String key, @NonNull String name);
	}

	private final Context context;
	private final Remover remover;
	// The library's session, or null while it is not known, and how
	// many shaders it holds, or -1 while that is not known.
	private String library;
	private int shaders = -1;
	// What was found to be lost when it was gone into.
	private final Set<String> lost = new HashSet<>();
	private List<ProjectSessions.Recent> sessions = List.of();

	FoldersSource(@NonNull Context context, @NonNull Remover remover) {
		this.context = context;
		this.remover = remover;
	}

	/**
	 * Takes the session of the library, which the row Shaders leads
	 * into and Recent does not list, and how many shaders it holds, -1
	 * when that is not known.
	 */
	void setLibrary(@Nullable String key, int shaders) {
		library = key;
		this.shaders = shaders;
	}

	/** Takes the recent projects, the one opened last first. */
	void setRecent(@NonNull List<ProjectSessions.Recent> sessions) {
		this.sessions = sessions;
	}

	void setLost(@NonNull String key, boolean isLost) {
		if (isLost) {
			lost.add(key);
		} else {
			lost.remove(key);
		}
	}

	/** The folder or file opened before with key, or null. */
	Recent find(@NonNull String key) {
		for (Recent opened : recent()) {
			if (opened.key().equals(key)) {
				return opened;
			}
		}
		return null;
	}

	@Override
	public Listing list(Order order) {
		List<Row> top = new ArrayList<>();
		if (library != null) {
			top.add(folder(Level.root(library), Row.Picture.Icon.LIBRARY,
					context.getString(R.string.nav_shaders), shaders < 0
							? null
							: shaders == 0
							? context.getString(R.string.nav_no_shaders)
							: context.getResources().getQuantityString(
									R.plurals.nav_shaders_count, shaders,
									shaders),
					EnumSet.noneOf(Row.Mark.class)));
		}
		List<Listing.Section> sections = new ArrayList<>();
		sections.add(new Listing.Section(null, List.of(
				folder(Level.EXAMPLES, Row.Picture.Icon.EXAMPLES,
						context.getString(R.string.load_sample),
						context.getString(R.string.nav_examples_count,
								Samples.ALL.size()),
						EnumSet.of(Row.Mark.READ_ONLY)))));
		top.addAll(sections.remove(0).rows());
		sections.add(0, new Listing.Section(null, top));
		List<Recent> recent = recent();
		if (!recent.isEmpty()) {
			sections.add(new Listing.Section(
					context.getString(R.string.nav_recent),
					Recent.rows(recent,
							context.getString(R.string.nav_single_file),
							context.getString(R.string.nav_cannot_be_opened))));
		}
		return new Listing(context.getString(R.string.nav_folders),
				null,
				false,
				false,
				Listing.Bar.OPEN,
				sections,
				null);
	}

	@Override
	public boolean open(Row row, boolean run) {
		return false;
	}

	@Override
	public void act(Row row, Row.Action action) {
		if (action == Row.Action.REMOVE) {
			remover.remove(row.key(), row.name());
		}
	}

	@NonNull
	private List<Recent> recent() {
		List<Recent> recent = new ArrayList<>();
		for (ProjectSessions.Recent session : sessions) {
			ProjectSessions.Kind kind = session.locator().kind();
			if (kind != ProjectSessions.Kind.DATABASE &&
					!session.id().equals(library)) {
				recent.add(new Recent(session.id(),
						kind == ProjectSessions.Kind.DOCUMENT,
						session.name(), session.location(),
						session.opened(), session.unsaved(),
						lost.contains(session.id())));
			}
		}
		return recent;
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
