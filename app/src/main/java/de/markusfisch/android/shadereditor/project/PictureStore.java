package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What is kept by path about the files of one folder, in app data: the
 * pictures of its shaders, and for the library also its settings. They
 * follow the renames and deletions its project makes (FILES 5.1a): a
 * move is noted before it is followed and taken off once everything
 * followed it, so one that cannot follow now is never dropped and is
 * followed the next time, and while it is noted a file made under the
 * old name shows no picture of the file that left it. The library's and
 * every other folder's pictures are kept in one of these.
 */
public final class PictureStore {
	/** The revision of a picture that came with the app, not a capture. */
	public static final String BUNDLED = "bundled";

	/** Something else kept by path that follows the same moves. */
	interface Follower {
		/** Return whether nothing is left behind under the old path. */
		boolean follow(Journal.Move move);
	}

	/** Why nothing may be written now, or null; checked before writing. */
	interface Entry {
		String enter();
	}

	private final File previews;
	private final File following;
	private final StateFile.Gate gate;
	private final Entry entry;
	private final List<Follower> others;
	// The moves that could not even be noted, by the note they wait
	// for, for as long as the app runs: a store is made anew for each
	// use. Until they are noted, the pictures are shown as if they
	// were, so a file made under a name another file left never shows
	// its picture.
	private static final Map<String, List<Journal.Move>> UNNOTED =
			new HashMap<>();
	private final List<Journal.Move> unnoted;

	/**
	 * @param folder the store's own folder: its pictures in
	 *               "previews", its notes in "following"
	 * @param gate   whether anything may be written now
	 * @param entry  what is checked before a picture is written, or
	 *               null for following the noted moves
	 * @param others what else follows the same moves
	 */
	PictureStore(File folder, StateFile.Gate gate, Entry entry,
			List<Follower> others) {
		this.previews = new File(folder, "previews");
		this.following = new File(folder, "following");
		this.gate = gate;
		this.entry = entry;
		this.others = others;
		synchronized (UNNOTED) {
			String key = following.getAbsolutePath();
			List<Journal.Move> moves = UNNOTED.get(key);
			if (moves == null) {
				moves = new ArrayList<>();
				UNNOTED.put(key, moves);
			}
			unnoted = moves;
		}
	}

	/** The pictures, as they are kept under the noted moves. */
	public Previews previews() {
		return new Previews(previews, following(), gate);
	}

	/**
	 * Keep png as the picture of path, made from its content at
	 * revision, once what is noted is followed. Return why it is not
	 * kept, or null.
	 */
	String put(ProjectPath path, String revision, byte[] png) {
		String refused = entry != null
				? entry.enter()
				: follow() ? null : "the pictures of the folder have not " +
						"followed a rename yet; try again";
		if (refused != null) {
			return refused;
		}
		return previews().put(path, revision, png)
				? null
				: "the picture cannot be kept";
	}

	/**
	 * Let everything follow the noted moves, in their order. Return
	 * whether none is left to follow.
	 */
	boolean follow() {
		Following noted = following();
		List<Journal.Move> moves;
		try {
			moves = noted.read();
		} catch (IOException e) {
			return false;
		}
		Previews pictures = new Previews(previews, null, gate);
		// What else follows goes first and the pictures last, as the
		// library's settings always went before its pictures.
		for (Journal.Move move : moves) {
			for (Follower other : others) {
				if (!other.follow(move)) {
					return false;
				}
			}
			if (!pictures.follow(move) || !noted.done(move)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * What follows the folder's files when its project renames or
	 * deletes one: each move is noted first, then followed.
	 */
	FileTexts.Kept kept() {
		follow();
		return move -> {
			synchronized (unnoted) {
				unnoted.add(move);
				Following noted = following();
				while (!unnoted.isEmpty() && noted.add(unnoted.get(0))) {
					unnoted.remove(0);
				}
				if (!unnoted.isEmpty()) {
					return false;
				}
			}
			// Noted, it is not lost when it cannot follow now.
			follow();
			return true;
		};
	}

	private Following following() {
		synchronized (unnoted) {
			return new Following(following, gate, new ArrayList<>(unnoted));
		}
	}
}
