package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the move of the database shaders into the library has done so
 * far: lines of text, appended and synced, so that a run after one
 * that died goes on where it stopped. Only a line with its line end
 * was written whole. What follows the last line end is a write that
 * did not finish: it is not read, and it is cut off before the next
 * line is appended, so that it can never become a line.
 * <p>
 * Anyone may read it, off the main thread. Only the queue the
 * projects work on appends to it, and nothing is appended to a note
 * that is there and cannot be read.
 */
public final class MoveNote {
	private static final String NAME = "name";
	private static final String MOVED = "moved";
	private static final String FILE = "file";
	private static final String SELECTED = "selected";

	private final File file;
	private final Map<Long, String> names = new HashMap<>();
	private final Map<Long, String> moved = new HashMap<>();
	private final Map<String, String> files = new HashMap<>();
	private boolean selected;
	// How many bytes of the file are whole lines, or -1 when the file
	// is there and cannot be read.
	private long whole;

	private final StateFile.Gate gate;

	MoveNote(File file) {
		this(file, StateFile.Gate.OPEN);
	}

	MoveNote(File file, StateFile.Gate gate) {
		this.file = file;
		this.gate = gate;
		load();
	}

	/**
	 * Read the file again, as another writer may have left it. Return
	 * whether it could be read, or is not there.
	 */
	synchronized boolean load() {
		names.clear();
		moved.clear();
		files.clear();
		selected = false;
		byte[] bytes;
		try {
			bytes = StateFile.read(file);
		} catch (IOException e) {
			whole = -1;
			return false;
		}
		int end = 0;
		for (int i = 0; bytes != null && i < bytes.length; ++i) {
			if (bytes[i] == '\n') {
				read(new String(bytes, end, i - end, StandardCharsets.UTF_8));
				end = i + 1;
			}
		}
		whole = end;
		return true;
	}

	private void read(String line) {
		String[] fields = line.split("\t", 3);
		if (fields.length != 3) {
			return;
		}
		try {
			if (fields[0].equals(NAME)) {
				names.put(Long.parseLong(fields[1]), fields[2]);
			} else if (fields[0].equals(MOVED)) {
				moved.put(Long.parseLong(fields[1]), fields[2]);
			} else if (fields[0].equals(FILE)) {
				files.put(fields[2], fields[1]);
			} else if (fields[0].equals(SELECTED)) {
				selected = true;
			}
		} catch (NumberFormatException e) {
			// A line nothing wrote says nothing.
		}
	}

	/** Return whether the note is there and cannot be read. */
	public synchronized boolean unreadable() {
		return whole < 0;
	}

	/**
	 * Return the file the database shader id was moved to, as it was
	 * named then, or null when it is not moved.
	 */
	public synchronized ProjectPath moved(long id) {
		String name = moved.get(id);
		ProjectParseResult<ProjectPath> path = name != null
				? ProjectPath.parse(name)
				: null;
		return path != null && path.isSuccess() ? path.value() : null;
	}

	/**
	 * Return the file name noted last for the database shader id, moved
	 * or not, or null when none is.
	 */
	synchronized String name(long id) {
		String name = moved.get(id);
		return name != null ? name : names.get(id);
	}

	/** Return every file name noted for a shader. */
	synchronized List<String> names() {
		List<String> all = new ArrayList<>(names.values());
		all.addAll(moved.values());
		return all;
	}

	/**
	 * Return the revision of the bytes the move wrote to path, a
	 * texture or a shader it generated, or null when it wrote none.
	 */
	synchronized String file(ProjectPath path) {
		return files.get(path.toString());
	}

	/** Return whether what the library shows first was settled. */
	synchronized boolean selected() {
		return selected;
	}

	/** Note that shader id gets the file name. Whether it is kept. */
	synchronized boolean noteName(long id, String name) {
		if (!append(NAME + "\t" + id + "\t" + name)) {
			return false;
		}
		names.put(id, name);
		return true;
	}

	/** Note that shader id is moved to name. Whether it is kept. */
	synchronized boolean noteMoved(long id, String name) {
		if (!append(MOVED + "\t" + id + "\t" + name)) {
			return false;
		}
		moved.put(id, name);
		return true;
	}

	/** Note that path was written at revision. Whether it is kept. */
	synchronized boolean noteFile(String revision, ProjectPath path) {
		if (!append(FILE + "\t" + revision + "\t" + path)) {
			return false;
		}
		files.put(path.toString(), revision);
		return true;
	}

	/**
	 * Note that what the library shows first is settled, for the shader
	 * id or for none when 0. Whether it is kept.
	 */
	synchronized boolean noteSelected(long id) {
		if (!append(SELECTED + "\t" + id + "\t-")) {
			return false;
		}
		selected = true;
		return true;
	}

	private boolean append(String line) {
		File folder = file.getParentFile();
		if (!gate.open() || whole < 0 || line.indexOf('\n') > -1 ||
				folder == null ||
				(!folder.isDirectory() && !folder.mkdirs())) {
			return false;
		}
		byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
		try {
			if (file.length() != whole) {
				// What a write that did not finish left goes first. A
				// death here leaves whole lines only.
				try (RandomAccessFile cut = new RandomAccessFile(file,
						"rw")) {
					cut.setLength(whole);
					cut.getFD().sync();
				}
			}
			try (FileOutputStream out = new FileOutputStream(file, true)) {
				out.write(bytes);
				out.getFD().sync();
			}
			whole += bytes.length;
			return true;
		} catch (IOException e) {
			// What was written of it has no line end, or is a whole
			// line that the next load reads; either way it is cut or
			// read, never joined to another.
			return false;
		}
	}
}
