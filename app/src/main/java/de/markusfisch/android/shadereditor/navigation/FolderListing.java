package de.markusfisch.android.shadereditor.navigation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.FileKind;
import de.markusfisch.android.shadereditor.project.FileRole;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.ProjectManifest;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * The sections a level of a folder opened before lists, from what is
 * known of it, with nothing read and nothing kept: the files the
 * running shader's last build read (Running), then the sections the
 * folder's README gives it, then the rest (All files, or Other files
 * after a README's sections), in pages. Each row has its face, line,
 * marks and menu. A heading is shown only when a section stands above
 * it.
 */
public final class FolderListing {
	/** The Running section shows this many files before Show all. */
	public static final int RUNNING_SHOWN = 6;
	/** The key of the row that shows all files of Running. */
	public static final String ALL_RUNNING = "\u0000running";

	/** The words a listing says, in the user's language. */
	public interface Words {
		/** "Running · Ink/main.frag". */
		String running(String shader);

		String allFiles();

		String otherFiles();

		String readmeOrders();

		String showMore();

		/** Like "%1$d of %2$d". */
		String shownOf();

		String showAllRunning(int count);

		String role(FileRole.Kind kind, String of);

		/** "Cannot be opened here · 2.1 MB". */
		String cannotOpen(long bytes);

		/** "Runs main.frag". */
		String runs(String entry);

		/** "Default shader". */
		String defaultShader();

		/** What the default shader's row says: "Tap to run it". */
		String defaultShaderLine();

		/** When a file was changed, from milliseconds since the epoch. */
		String changed(long time);

		/** The heading of unsaved text whose file is gone. */
		String notInFolder();
	}

	/** Whether a file of the folder can run, as far as it is known. */
	public interface Runs {
		OpenProject.Runs of(String key, String name);
	}

	/**
	 * What is known of a level: its reading, the pictures by path, the
	 * file the wallpaper shows, the manifest's line (what it runs or
	 * why not), the file that runs by the rule of SPEC 1 before it was
	 * built here, offered as the default shader, the README's
	 * sections, the files the running shader's last build read and
	 * whether all of them are shown, the file that runs, whether the
	 * folder can be changed, the paths of the folders read that can be
	 * changed ("" for the root), the files with unsaved text, those of
	 * them another app changed or deleted, and those of them whose file
	 * storage was last found not to hold.
	 */
	public record Facts(Level level, Order order, Reading reading,
			Map<String, byte[]> pictures, String wallpaper,
			String manifestLine, String defaultShader,
			List<Readme.Section> readme,
			List<OpenProject.Used> used, boolean allRunning, String running,
			Runs runs, boolean writable, Set<String> writableFolders,
			Set<String> unsaved, Set<String> outside, Set<String> gone) {
	}

	private FolderListing() {
	}

	/**
	 * The sections of the level, as the class says; at the root first
	 * the files with unsaved text whose file is gone, wherever they
	 * were, so that no unsaved text is out of reach.
	 */
	public static List<Listing.Section> sections(Facts facts, Words words) {
		List<Listing.Section> sections = new ArrayList<>();
		String level = facts.level().path();
		if ((level == null || level.isEmpty()) && !facts.gone().isEmpty()) {
			List<String> keys = new ArrayList<>(facts.gone());
			Collections.sort(keys);
			List<Row> rows = new ArrayList<>(keys.size());
			for (String key : keys) {
				String name = key.substring(key.lastIndexOf('/') + 1);
				rows.add(fileRow(facts, words, key,
						FileKind.hint(name, null) == FileKind.PICTURE
								? Row.Kind.IMAGE
								: Row.Kind.TEXT, null));
			}
			sections.add(new Listing.Section(words.notInFolder(),
					marked(rows, facts)));
		}
		List<Row> running = running(facts, words);
		if (!running.isEmpty()) {
			// Named as its row names it, by its path from the root.
			String shader = facts.running() != null
					? facts.running()
					: running.get(0).name();
			sections.add(new Listing.Section(words.running(shader),
					marked(running, facts)));
		} else if (facts.defaultShader() != null &&
				facts.pictures().get(facts.defaultShader()) == null) {
			// It was not built here yet, as it has no picture: what
			// would run comes first, also from a folder below, as a
			// manifest's "passes/main.frag", named by its path then.
			List<Row> named = facts.defaultShader().indexOf('/') < 0
					? files(facts, words, facts.reading().rowsNamed(
							facts.level(), List.of(facts.defaultShader())))
					: List.of(fileRow(facts, words, facts.defaultShader(),
							Row.Kind.TEXT, null));
			for (Row row : named) {
				sections.add(new Listing.Section(words.defaultShader(),
						List.of(withDetail(row, words.defaultShaderLine()))));
			}
		}
		// The entries the README takes leave the pages to the rest.
		Set<String> linked = new LinkedHashSet<>();
		for (Readme.Section section : facts.readme()) {
			for (Readme.Linked entry : section.entries()) {
				linked.add(entry.name());
			}
		}
		Reading reading = facts.reading();
		Map<String, Row> named = new HashMap<>();
		for (Row row : files(facts, words, reading.rowsNamed(facts.level(),
				new ArrayList<>(linked)))) {
			named.put(row.name(), row);
		}
		// What the README links that is not there stays in the pages.
		Set<String> taken = new HashSet<>(named.keySet());
		List<Row> rest = new ArrayList<>(files(facts, words, reading.rows(
				facts.level(), words.showMore(), words.shownOf(),
				facts.order(), taken)));
		List<Row> untitled = new ArrayList<>();
		List<Listing.Section> described = new ArrayList<>();
		for (Readme.Section section : facts.readme()) {
			List<Row> rows = new ArrayList<>();
			for (Readme.Linked entry : section.entries()) {
				Row row = named.get(entry.name());
				if (row != null) {
					rows.add(entry.line() != null
							? withDetail(row, entry.line())
							: Readme.is(row.name())
							? withDetail(row, words.readmeOrders())
							: row);
				}
			}
			if (rows.isEmpty()) {
				continue;
			} else if (section.heading() != null) {
				described.add(new Listing.Section(section.heading(), rows));
			} else {
				untitled.addAll(rows);
			}
		}
		if (!described.isEmpty() || !untitled.isEmpty()) {
			// The README says that it orders the list.
			rest = ordering(rest, words);
		}
		// Links before the first heading: first, when nothing stands
		// above them, neither Running nor the default shader; else at
		// the start of the rest, which has a heading.
		if (!untitled.isEmpty() && sections.isEmpty()) {
			sections.add(new Listing.Section(null, untitled));
		} else {
			rest.addAll(0, untitled);
		}
		sections.addAll(described);
		if (!rest.isEmpty() || sections.isEmpty()) {
			sections.add(new Listing.Section(sections.isEmpty()
					? null
					: !described.isEmpty() || !untitled.isEmpty()
					? words.otherFiles()
					: words.allFiles(), rest));
		}
		return sections;
	}

	// The rows of files of the folder with their faces, lines, marks and
	// menus.
	private static List<Row> files(Facts facts, Words words,
			List<Row> rows) {
		Map<String, Long> sizes = new HashMap<>();
		for (Reading.Entry entry : facts.reading().entries()) {
			sizes.put(entry.name(), entry.size());
		}
		Set<String> passes = passes(facts);
		List<Row> dressed = new ArrayList<>(rows.size());
		Map<String, OpenProject.Runs> runs = new HashMap<>();
		for (Row row : rows) {
			if (row.kind() == Row.Kind.MORE) {
				dressed.add(row);
				continue;
			}
			byte[] picture = facts.pictures().get(row.key());
			if (row.kind() == Row.Kind.FOLDER) {
				dressed.add(new Row(row.key(), row.kind(),
						Face.of(row.kind(), row.name(), picture), row.name(),
						row.detail(), row.changed(), row.enters(),
						row.marks(), row.actions()));
				continue;
			}
			OpenProject.Runs known = facts.runs().of(row.key(), row.name());
			if (known != null) {
				runs.put(row.key(), known);
			}
			Set<Row.Mark> marks = EnumSet.noneOf(Row.Mark.class);
			marks.addAll(row.marks());
			if (row.key().equals(facts.wallpaper())) {
				marks.add(Row.Mark.WALLPAPER);
			}
			Long size = sizes.get(row.name());
			// An image's line is its format and size, once read.
			String detail = row.kind() == Row.Kind.IMAGE
					? null
					: row.kind() == Row.Kind.OTHER
					? words.cannotOpen(size != null ? size : 0)
					: facts.manifestLine() != null &&
					row.key().equals(ProjectManifest.FILE.toString())
					? facts.manifestLine()
					: words.changed(row.changed());
			dressed.add(new Row(row.key(), row.kind(),
					passes.contains(row.key())
							? Face.of(Face.Of.PASS, row.name(), picture, true)
							: Face.of(row.kind(), row.name(), picture),
					row.name(), detail, row.changed(), row.enters(), marks,
					row.actions()));
		}
		return marked(FileActions.of(dressed, facts.running(), runs,
				facts.writable()), facts);
	}

	private static List<Row> marked(List<Row> rows, Facts facts) {
		return FileMarks.outside(FileMarks.unsaved(rows, facts.unsaved()),
				facts.outside());
	}

	// The files the last build of what runs read, in build order, each
	// with its path from the root and what it is to the shader: when
	// that is more than the file that runs. Six, then Show all.
	private static List<Row> running(Facts facts, Words words) {
		List<OpenProject.Used> used = facts.used();
		List<Row> rows = new ArrayList<>();
		if (used.size() < 2) {
			return rows;
		}
		int shown = facts.allRunning() || used.size() <= RUNNING_SHOWN + 1
				? used.size()
				: RUNNING_SHOWN;
		for (OpenProject.Used file : used.subList(0, shown)) {
			rows.add(fileRow(facts, words, file.path().toString(), file.image()
					? Row.Kind.IMAGE
					: Row.Kind.TEXT, file.role()));
		}
		if (shown < used.size()) {
			rows.add(new Row(ALL_RUNNING, Row.Kind.MORE,
					Face.of(Row.Kind.MORE, null, null),
					words.showAllRunning(used.size()), null, 0, null,
					EnumSet.noneOf(Row.Mark.class),
					EnumSet.noneOf(Row.Action.class)));
		}
		return rows;
	}

	// The files that are passes of what runs, by their paths.
	private static Set<String> passes(Facts facts) {
		Set<String> passes = new HashSet<>();
		for (OpenProject.Used file : facts.used()) {
			if (isPass(file.role())) {
				passes.add(file.path().toString());
			}
		}
		return passes;
	}

	private static boolean isPass(FileRole role) {
		return role != null && (role.kind() == FileRole.Kind.PASS ||
				role.kind() == FileRole.Kind.FIRST_FRAME);
	}

	// The row of the file at key, a path from the root, shown at a level
	// that may not be its folder, named by that path: its face, its
	// role in what runs as its line, and its menu as in its own folder,
	// with changes only where that folder was read and can be changed.
	private static Row fileRow(Facts facts, Words words, String key,
			Row.Kind kind, FileRole role) {
		int slash = key.lastIndexOf('/');
		String name = key.substring(slash + 1);
		byte[] picture = facts.pictures().get(key);
		Row row = new Row(key, kind, isPass(role)
				? Face.of(Face.Of.PASS, key, picture, true)
				: Face.of(kind, name, picture), key, role(role, words), 0,
				null, EnumSet.noneOf(Row.Mark.class),
				EnumSet.noneOf(Row.Action.class));
		Map<String, OpenProject.Runs> runs = new HashMap<>();
		OpenProject.Runs known = facts.runs().of(key, name);
		if (known != null) {
			runs.put(key, known);
		}
		return FileActions.of(List.of(row), facts.running(), runs,
				facts.writableFolders().contains(slash > -1
						? key.substring(0, slash)
						: "")).get(0);
	}

	private static String role(FileRole role, Words words) {
		if (role == null) {
			return null;
		}
		List<String> names = new ArrayList<>();
		for (ProjectPath to : role.of()) {
			names.add(to.getFileName());
		}
		return words.role(role.kind(), String.join(", ", names));
	}

	// The rows, a README among them saying that it orders the list.
	private static List<Row> ordering(List<Row> rows, Words words) {
		List<Row> said = new ArrayList<>(rows.size());
		for (Row row : rows) {
			said.add(Readme.is(row.name())
					? withDetail(row, words.readmeOrders())
					: row);
		}
		return said;
	}

	private static Row withDetail(Row row, String detail) {
		return new Row(row.key(), row.kind(), row.picture(), row.name(),
				detail, row.changed(), row.enters(), row.marks(),
				row.actions());
	}
}
