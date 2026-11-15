package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

/**
 * What an archive of the library is, for the one that writes it and
 * the one that reads it: a zip of the library's files under their
 * paths, and first in it a small text file with what the app keeps
 * beside them. Both sides hold to the same limits and the same rule
 * for names, so that an archive that was written is one that is read.
 */
final class LibraryArchive {
	/**
	 * The most an archive may hold: entries, and bytes of all its files
	 * together.
	 */
	record Limits(int entries, long bytes) {
		static final Limits DEFAULT = new Limits(20000, 1L << 30);
	}

	/** Why an archive is not written or not read, for the user. */
	static final class Refused extends Exception {
		Refused(String reason) {
			super(reason);
		}
	}

	/**
	 * What the text file says beside the files: the shader new ones are
	 * copied from, or null, and the qualities that are not 1, by path.
	 */
	record Facts(String template, Map<String, Float> qualities) {
		static final Facts NONE = new Facts(null, Map.of());

		Facts {
			qualities = new TreeMap<>(qualities);
		}
	}

	/** The entry of the text file; no file of a library has a dot name. */
	static final String FACTS = ".shadereditor/library.txt";
	/** The most bytes of it that are read. */
	static final int FACTS_BYTES = 1 << 20;

	private static final int FORMAT = 1;
	private static final float MOST_QUALITY = 2f;
	// The longest name the storage of a phone holds, in bytes.
	private static final int LONGEST = 255;
	private static final Naming NAMING = new Naming(List.of());

	private LibraryArchive() {
	}

	/**
	 * Return the text file of an archive app wrote at time: one fact a
	 * line, its fields apart by tabs, a path last.
	 */
	static byte[] text(String app, long time, Facts facts) {
		SimpleDateFormat utc = new SimpleDateFormat(
				"yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
		utc.setTimeZone(TimeZone.getTimeZone("UTC"));
		StringBuilder sb = new StringBuilder();
		sb.append("format\t").append(FORMAT).append('\n');
		sb.append("app\t").append(app).append('\n');
		sb.append("exported\t").append(utc.format(new Date(time)))
				.append('\n');
		if (facts.template() != null) {
			sb.append("template\t").append(facts.template()).append('\n');
		}
		for (Map.Entry<String, Float> quality :
				facts.qualities().entrySet()) {
			sb.append("quality\t").append(quality.getValue()).append('\t')
					.append(quality.getKey()).append('\n');
		}
		return sb.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * Read the text file of an archive. A line it does not know says
	 * nothing; a format above the one written here is refused.
	 */
	static Facts facts(byte[] text) throws Refused {
		String template = null;
		Map<String, Float> qualities = new HashMap<>();
		for (String line : new String(text, StandardCharsets.UTF_8)
				.split("\r?\n")) {
			String[] fields = line.split("\t", 3);
			try {
				if (fields.length == 2 && fields[0].equals("format") &&
						Integer.parseInt(fields[1]) > FORMAT) {
					throw new Refused("it was made by a newer version of " +
							"ShaderEditor (format " + fields[1] + ")");
				} else if (fields.length == 2 &&
						fields[0].equals("template")) {
					template = fields[1];
				} else if (fields.length == 3 &&
						fields[0].equals("quality")) {
					float quality = Float.parseFloat(fields[1]);
					// Nothing draws at another size than these.
					if (quality > 0f && quality <= MOST_QUALITY) {
						qualities.put(fields[2], quality);
					}
				}
			} catch (NumberFormatException e) {
				// A line nothing wrote; it says nothing.
			}
		}
		return new Facts(template, qualities);
	}

	/**
	 * The names of the entries of one archive, checked as they come: a
	 * name must be a path inside the library that a phone's storage can
	 * hold beside the others.
	 */
	static final class Names {
		// By the name without regard to case: how it is spelled.
		private final Map<String, String> files = new HashMap<>();
		private final Map<String, String> folders = new HashMap<>();
		private final Set<String> filled = new HashSet<>();
		private final List<ProjectPath> listed = new ArrayList<>();

		/**
		 * Take name as a file of the archive. Return its path, or null
		 * for a hidden one, which is no file of a library.
		 */
		ProjectPath file(String name) throws Refused {
			ProjectPath path = path(name);
			if (path == null) {
				return null;
			}
			String key = fold(path.toString());
			String taken = files.get(key);
			if (taken != null) {
				throw new Refused(taken.equals(path.toString())
						? show(name) + " is in it twice"
						: clash(taken, path));
			} else if (folders.containsKey(key)) {
				throw new Refused(show(name) + " is a file and a folder");
			}
			files.put(key, path.toString());
			return path;
		}

		/** Take name, with or without its last slash, as a folder. */
		void folder(String name) throws Refused {
			ProjectPath path = path(name.endsWith("/")
					? name.substring(0, name.length() - 1)
					: name);
			if (path != null) {
				enter(path.toString());
				listed.add(path);
			}
		}

		/** Return the folders that were named and hold nothing. */
		List<ProjectPath> empty() {
			List<ProjectPath> empty = new ArrayList<>();
			for (ProjectPath folder : listed) {
				if (!filled.contains(fold(folder.toString())) &&
						!empty.contains(folder)) {
					empty.add(folder);
				}
			}
			return empty;
		}

		// The path name is, with the folders above it taken; null for
		// a hidden name, which still fills the folders above it.
		private ProjectPath path(String name) throws Refused {
			String[] parts = name.split("/", -1);
			boolean hidden = false;
			for (String part : parts) {
				Naming.Problem problem = part.isEmpty() ? Naming.Problem.EMPTY
						: NAMING.check(part);
				if (problem == Naming.Problem.EMPTY || part.equals(".") ||
						part.equals("..")) {
					throw new Refused(show(name) +
							" is not a name inside a library");
				} else if (problem == Naming.Problem.INVALID_CHARACTER) {
					throw new Refused(show(name) + " has a character no " +
							"folder holds in a name");
				} else if (part.getBytes(StandardCharsets.UTF_8).length >
						LONGEST) {
					throw new Refused(show(name) + " is longer than a " +
							"name may be");
				}
				hidden |= problem == Naming.Problem.HIDDEN;
			}
			StringBuilder above = new StringBuilder();
			for (int i = 0; i < parts.length - 1 &&
					!parts[i].startsWith("."); ++i) {
				above.append(i > 0 ? "/" : "").append(parts[i]);
				ProjectParseResult<ProjectPath> folder =
						ProjectPath.parse(above.toString());
				if (folder.isSuccess()) {
					filled.add(enter(folder.value().toString()));
				}
			}
			if (hidden) {
				return null;
			}
			ProjectParseResult<ProjectPath> path = ProjectPath.parse(name);
			if (!path.isSuccess()) {
				throw new Refused(show(name) +
						" is not a name inside a library");
			}
			return path.value();
		}

		// Take folder as one; return its name without regard to case.
		private String enter(String folder) throws Refused {
			String key = fold(folder);
			String taken = folders.get(key);
			if (files.containsKey(key)) {
				throw new Refused(show(folder) + " is a file and a folder");
			} else if (taken != null && !taken.equals(folder)) {
				throw new Refused(show(taken) + " and " + show(folder) +
						" differ only by case, which a phone cannot keep " +
						"apart");
			}
			folders.put(key, folder);
			return key;
		}

		private static String clash(String taken, ProjectPath path) {
			return show(taken) + " and " + show(path.toString()) +
					" differ only by case, which a phone cannot keep apart";
		}

		private static String fold(String path) {
			return path.toLowerCase(Locale.ROOT);
		}
	}

	// A name as a message shows it, whatever it holds.
	static String show(String name) {
		String shown = name.length() > 120
				? name.substring(0, 120) + "…"
				: name;
		return "\"" + shown.replaceAll("\\p{Cntrl}", "?") + "\"";
	}
}
