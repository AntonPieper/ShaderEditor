package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The renames and deletions of library files that storage made and
 * that what is kept by path has not followed yet, in their order. One
 * is noted here as soon as storage made it, and taken off when the
 * settings and pictures followed, so it is not lost when they cannot
 * follow at once, whatever is renamed or made under the old name
 * meanwhile. Until then a reader is told where what it asks for is
 * still kept, and that nothing is kept for a path a file left.
 */
final class Following {
	private final File file;
	private final StateFile.Gate gate;

	Following(File file) {
		this(file, StateFile.Gate.OPEN);
	}

	Following(File file, StateFile.Gate gate) {
		this.file = file;
		this.gate = gate;
	}

	/**
	 * Return what is noted, in order; nothing when there is no note.
	 *
	 * @throws IOException when it is there and cannot be read
	 */
	synchronized List<Journal.Move> read() throws IOException {
		List<Journal.Move> moves = new ArrayList<>();
		byte[] bytes = StateFile.read(file);
		if (bytes == null) {
			return moves;
		}
		// One a line: from, a tab, and to, which is empty for a
		// deletion. A path holds no tab and no line end.
		for (String line : new String(bytes, StandardCharsets.UTF_8)
				.split("\n")) {
			String[] fields = line.split("\t", -1);
			ProjectParseResult<ProjectPath> from = fields.length == 2
					? ProjectPath.parse(fields[0])
					: null;
			ProjectParseResult<ProjectPath> to = from != null &&
					!fields[1].isEmpty()
					? ProjectPath.parse(fields[1])
					: null;
			if (from != null && from.isSuccess() &&
					(to == null || to.isSuccess())) {
				moves.add(new Journal.Move(from.value(),
						to != null ? to.value() : null));
			}
		}
		return moves;
	}

	/** Note move behind what is noted. Return whether it is kept. */
	synchronized boolean add(Journal.Move move) {
		if (!gate.open()) {
			return false;
		}
		try {
			List<Journal.Move> moves = read();
			moves.add(move);
			return write(moves);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Take move, the one noted first, off. Return whether it is not
	 * the first anymore.
	 */
	synchronized boolean done(Journal.Move move) {
		if (!gate.open()) {
			return false;
		}
		try {
			List<Journal.Move> moves = read();
			if (moves.isEmpty() || !moves.get(0).equals(move)) {
				return true;
			}
			moves.remove(0);
			return moves.isEmpty() && file.delete() || write(moves);
		} catch (IOException e) {
			return false;
		}
	}

	/**
	 * Return the path what is kept for the file at now is still kept
	 * under, or null when nothing is kept for it: a file that was
	 * renamed left that path, so what is there now is another file. A
	 * note that cannot be read is taken as none, to show something.
	 */
	ProjectPath stored(ProjectPath now) {
		List<Journal.Move> moves = noted();
		for (int i = moves.size(); now != null && i-- > 0; ) {
			Journal.Move move = moves.get(i);
			if (move.to() != null &&
					new Journal.Move(move.to(), move.from()).touches(now)) {
				now = new Journal.Move(move.to(), move.from()).moved(now);
			} else if (move.touches(now)) {
				return null;
			}
		}
		return now;
	}

	/**
	 * Return where the file is now that something is kept for under
	 * stored, or null when it was deleted.
	 */
	ProjectPath current(ProjectPath stored) {
		for (Journal.Move move : noted()) {
			if (stored != null && move.touches(stored)) {
				stored = move.moved(stored);
			}
		}
		return stored;
	}

	private List<Journal.Move> noted() {
		try {
			return read();
		} catch (IOException e) {
			return List.of();
		}
	}

	private boolean write(List<Journal.Move> moves) {
		StringBuilder sb = new StringBuilder();
		for (Journal.Move move : moves) {
			sb.append(move.from()).append('\t')
					.append(move.to() != null ? move.to().toString() : "")
					.append('\n');
		}
		return StateFile.replace(file,
				sb.toString().getBytes(StandardCharsets.UTF_8));
	}
}
