package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * What the app keeps about the files of the library that a file has
 * no place for: the quality a shader is drawn at, and the shader new
 * ones are copied from. One small file, replaced whole in one rename.
 * <p>
 * Anyone may read it, off the main thread; a file that cannot be read
 * then shows as the defaults. Only the queue the projects work on
 * changes it, and a change is refused while the file cannot be read,
 * so that what it holds is never written over by less.
 */
public final class LibrarySettings {
	private static final String QUALITY = "quality";
	private static final String TEMPLATE = "template";

	private final File file;

	LibrarySettings(File file) {
		this.file = file;
	}

	/** Return the quality of the shader at path, 1 when none is kept. */
	public float quality(ProjectPath path) {
		try {
			return read().quality(path);
		} catch (IOException e) {
			return 1f;
		}
	}

	/** Return the shader new ones are copied from, or null for none. */
	public ProjectPath template() {
		try {
			return read().template();
		} catch (IOException e) {
			return null;
		}
	}

	/** What the file holds. */
	static final class State {
		private final Map<String, Float> qualities = new LinkedHashMap<>();
		private String template;

		float quality(ProjectPath path) {
			Float quality = qualities.get(path.toString());
			return quality != null ? quality : 1f;
		}

		ProjectPath template() {
			ProjectParseResult<ProjectPath> path = template != null
					? ProjectPath.parse(template)
					: null;
			return path != null && path.isSuccess() ? path.value() : null;
		}
	}

	/**
	 * Return what the file holds, nothing when there is none.
	 *
	 * @throws IOException when it is there and cannot be read
	 */
	synchronized State read() throws IOException {
		State state = new State();
		byte[] bytes = StateFile.read(file);
		if (bytes == null) {
			return state;
		}
		// One fact a line, its fields apart by tabs, the path last: a
		// name holds no tab and no line end.
		for (String line : new String(bytes, StandardCharsets.UTF_8)
				.split("\n")) {
			String[] fields = line.split("\t", 3);
			if (fields.length == 2 && fields[0].equals(TEMPLATE)) {
				state.template = fields[1];
			} else if (fields.length == 3 && fields[0].equals(QUALITY)) {
				try {
					state.qualities.put(fields[2],
							Float.parseFloat(fields[1]));
				} catch (NumberFormatException e) {
					// A line nothing wrote; the default applies.
				}
			}
		}
		return state;
	}

	/** Keep quality for the shader at path. Return whether it is kept. */
	synchronized boolean setQuality(ProjectPath path, float quality) {
		State state;
		try {
			state = read();
		} catch (IOException e) {
			return false;
		}
		if (quality == 1f) {
			state.qualities.remove(path.toString());
		} else {
			state.qualities.put(path.toString(), quality);
		}
		return write(state);
	}

	/**
	 * Keep path as the shader new ones are copied from, none when null.
	 * Return whether it is kept.
	 */
	synchronized boolean setTemplate(ProjectPath path) {
		State state;
		try {
			state = read();
		} catch (IOException e) {
			return false;
		}
		state.template = path != null ? path.toString() : null;
		return write(state);
	}

	/**
	 * Follow a file or folder that was renamed or deleted: what was
	 * kept for it, or for a file under it, is kept for where it is
	 * now, or forgotten. Return whether that is kept.
	 */
	synchronized boolean follow(Journal.Move move) {
		State state;
		try {
			state = read();
		} catch (IOException e) {
			return false;
		}
		State next = new State();
		boolean changed = false;
		for (Iterator<Map.Entry<String, Float>> all =
				state.qualities.entrySet().iterator(); all.hasNext(); ) {
			Map.Entry<String, Float> quality = all.next();
			String now = followed(quality.getKey(), move);
			changed |= !quality.getKey().equals(now);
			if (now != null) {
				next.qualities.put(now, quality.getValue());
			}
		}
		next.template = state.template != null
				? followed(state.template, move)
				: null;
		changed |= state.template != null &&
				!state.template.equals(next.template);
		return !changed || write(next);
	}

	// Where path is after move, null when it was deleted.
	private static String followed(String path, Journal.Move move) {
		ProjectParseResult<ProjectPath> parsed = ProjectPath.parse(path);
		if (!parsed.isSuccess() || !move.touches(parsed.value())) {
			return path;
		}
		ProjectPath moved = move.moved(parsed.value());
		return moved != null ? moved.toString() : null;
	}

	private boolean write(State state) {
		StringBuilder sb = new StringBuilder();
		if (state.template != null) {
			sb.append(TEMPLATE).append('\t').append(state.template)
					.append('\n');
		}
		for (Map.Entry<String, Float> quality : state.qualities.entrySet()) {
			sb.append(QUALITY).append('\t')
					.append(String.format(Locale.ROOT, "%s",
							quality.getValue()))
					.append('\t').append(quality.getKey()).append('\n');
		}
		return StateFile.replace(file,
				sb.toString().getBytes(StandardCharsets.UTF_8));
	}
}
