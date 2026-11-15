package de.markusfisch.android.shadereditor.activity.managers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.navigation.Face;
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
	// The picture of what runs in each folder opened before, by its
	// session, the library's and the examples' among them.
	private Map<String, byte[]> pictures = Map.of();
	// The session of the examples, or null while it is not known, and
	// how many shaders they hold, or -1 while that is not known.
	@Nullable
	private String examples;
	private int exampleShaders = -1;

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

	/**
	 * Takes the picture of what runs in each folder opened before, by
	 * its session, the library's and the examples' among them.
	 */
	void setPictures(@NonNull Map<String, byte[]> pictures) {
		this.pictures = pictures;
	}

	/**
	 * Takes the session of the examples, which the row Examples leads
	 * into like any folder, and how many shaders they hold, -1 when
	 * that is not known.
	 */
	void setExamples(@Nullable String key, int shaders) {
		examples = key;
		exampleShaders = shaders;
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
			top.add(folder(Level.root(library), Face.of(Face.Of.LIBRARY,
							null, pictures.get(library), true),
					context.getString(R.string.nav_shaders), shaders < 0
							? null
							: shaders == 0
							? context.getString(R.string.nav_no_shaders)
							: context.getResources().getQuantityString(
									R.plurals.nav_shaders_count, shaders,
									shaders),
					EnumSet.noneOf(Row.Mark.class)));
		} else {
			// While the shaders move, the library is not open yet: its
			// row stands where it will be, so that the list does not
			// change shape under the user, and leads nowhere yet.
			top.add(new Row("library", Row.Kind.OTHER,
					Face.of(Face.Of.LIBRARY, null, null, true),
					context.getString(R.string.nav_shaders),
					context.getString(R.string.library_moving), 0, null,
					EnumSet.noneOf(Row.Mark.class),
					EnumSet.noneOf(Row.Action.class)));
		}
		// The examples are a read-only folder like any other.
		Row.Picture examplesFace = Face.of(Face.Of.EXAMPLES, null,
				examples != null ? pictures.get(examples) : null, true);
		String examplesCount = exampleShaders < 0
				? null
				: context.getString(R.string.nav_examples_count,
						exampleShaders);
		top.add(examples != null
				? folder(Level.root(examples), examplesFace,
						context.getString(R.string.load_sample), examplesCount,
						EnumSet.of(Row.Mark.READ_ONLY))
				: new Row("examples", Row.Kind.OTHER, examplesFace,
						context.getString(R.string.load_sample), null, 0, null,
						EnumSet.of(Row.Mark.READ_ONLY),
						EnumSet.noneOf(Row.Action.class)));
		List<Listing.Section> sections = new ArrayList<>();
		sections.add(new Listing.Section(null, top));
		List<Recent> recent = recent();
		if (!recent.isEmpty()) {
			sections.add(new Listing.Section(
					context.getString(R.string.nav_recent),
					Recent.rows(recent, pictures,
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
					kind != ProjectSessions.Kind.EXAMPLES &&
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
			@NonNull Row.Picture picture,
			@NonNull String name,
			String detail,
			@NonNull Set<Row.Mark> marks) {
		return new Row(level.name(), Row.Kind.FOLDER, picture,
				name, detail, 0, level, marks,
				EnumSet.noneOf(Row.Action.class));
	}
}
