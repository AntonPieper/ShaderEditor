package de.markusfisch.android.shadereditor.project;

import java.util.List;

/**
 * Storage of one opened project root, keyed by logical path. Nothing
 * throws: every outcome, also bad input and I/O trouble, is a value. A
 * store that cannot change files refuses every change as
 * {@link Refusal#UNSUPPORTED}, which is what the defaults do.
 */
public interface ProjectFiles {
	/**
	 * What reading a file found: {@link Content} or {@link Failed}.
	 */
	interface Read {
	}

	/**
	 * The bytes of a file and an opaque revision that changes whenever
	 * the bytes change.
	 */
	record Content(byte[] bytes, String revision) implements Read {
	}

	/**
	 * A file or folder that could not be read, for example because
	 * permission was lost; reason tells the user why.
	 */
	record Failed(String reason) implements Read, Listing {
	}

	/**
	 * What listing a folder found: {@link Entries} or {@link Failed}.
	 */
	interface Listing {
	}

	/**
	 * The entries of a folder, in no particular order.
	 */
	record Entries(List<Entry> entries) implements Listing {
		public Entries {
			entries = List.copyOf(entries);
		}
	}

	/** A size or time the storage did not tell. */
	long UNKNOWN = -1;

	/**
	 * One entry of a folder: its name in Unicode NFC, whether it is a
	 * folder, its size in bytes and when it last changed, in
	 * milliseconds since the epoch, as the listing told them, or
	 * {@link #UNKNOWN}. Two entries whose names normalize alike both
	 * appear; a path to either fails to read.
	 */
	record Entry(String name, boolean folder, long size, long modified) {
		/** An entry of which the storage told no size or time. */
		public Entry(String name, boolean folder) {
			this(name, folder, UNKNOWN, UNKNOWN);
		}
	}

	/**
	 * What a change did: {@link Done} or {@link Refused}.
	 */
	interface Change {
	}

	/**
	 * A change that happened. revision is the revision of the bytes
	 * written, or null when no file was written.
	 */
	record Done(String revision) implements Change {
	}

	/**
	 * A change that did not happen and changed nothing; reason tells
	 * the user why.
	 */
	record Refused(Refusal refusal, String reason) implements Change {
	}

	enum Refusal {
		/** The file is not at the revision the caller expected. */
		CONFLICT,
		/** The target exists, or the storage cannot tell them apart. */
		EXISTS,
		/** There is nothing at the path to change. */
		ABSENT,
		/** This store cannot make this change. */
		UNSUPPORTED,
		/** The storage failed, or the path is not usable here. */
		FAILED
	}

	/**
	 * Read a file: its content, why it could not be read, or null when
	 * there is no file at path.
	 */
	Read read(ProjectPath path);

	/**
	 * List a folder, the project root when folder is null: its entries,
	 * why it could not be listed, or null when there is no such folder.
	 */
	default Listing list(ProjectPath folder) {
		return new Failed("this storage cannot list folders");
	}

	/**
	 * Write a file if it is still at expectedRevision, the revision the
	 * caller last read, or still absent when that is null. Missing
	 * folders are created. A file at another revision is refused as
	 * {@link Refusal#CONFLICT} and left as it is.
	 */
	default Change write(ProjectPath path, byte[] bytes,
			String expectedRevision) {
		return unsupported();
	}

	/**
	 * Create an empty folder and any missing folders above it.
	 */
	default Change createFolder(ProjectPath folder) {
		return unsupported();
	}

	/**
	 * Rename or move a file or folder to a path that is not taken.
	 */
	default Change rename(ProjectPath from, ProjectPath to) {
		return unsupported();
	}

	/**
	 * Delete a file, or a folder with everything in it.
	 */
	default Change delete(ProjectPath path) {
		return unsupported();
	}

	private static Change unsupported() {
		return new Refused(Refusal.UNSUPPORTED, "this storage is read-only");
	}
}
