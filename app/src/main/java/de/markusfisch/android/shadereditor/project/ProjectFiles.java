package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
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
	 * The changes a file or folder offers: writing a file, creating in a
	 * folder, renaming, moving and deleting it.
	 */
	record Offers(boolean write, boolean create, boolean rename,
			boolean move, boolean delete) {
		public static final Offers NONE = new Offers(false, false, false,
				false, false);

		public boolean any() {
			return write || create || rename || move || delete;
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
	 * A change that did not happen; reason tells the user why. It
	 * changed nothing, unless its refusal is {@link Refusal#PARTIAL}.
	 */
	record Refused(Refusal refusal, String reason) implements Change {
	}

	/**
	 * Bytes to write, produced into a stream, so that a store that can
	 * stream never holds all of them at once.
	 */
	interface Source {
		void writeTo(OutputStream out) throws IOException;
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
		FAILED,
		/**
		 * The storage failed while changing the file, which may now hold
		 * part of the new bytes or be left behind: read it again before
		 * changing it again.
		 */
		PARTIAL
	}

	/** Why a file was not read: it is larger than the limit asked. */
	String TOO_LARGE = "it is too large";

	/**
	 * Read a file: its content, why it could not be read, or null when
	 * there is no file at path.
	 */
	Read read(ProjectPath path);

	/**
	 * Read a file as {@link #read(ProjectPath)} does, unless it has more
	 * than limit bytes: then fail with {@link #TOO_LARGE}. A store that
	 * reads a stream stops after limit bytes and a buffer.
	 */
	default Read read(ProjectPath path, long limit) {
		Read read = read(path);
		return read instanceof Content content &&
				content.bytes().length > limit
				? new Failed(TOO_LARGE)
				: read;
	}

	/**
	 * Return at most the first count bytes of the file at path, to tell
	 * what it is without reading it whole, or null when they are not
	 * known: there is no such file, or it cannot be read. A store that
	 * reads a stream stops after them. One that cannot answers only for
	 * a file of no more than count bytes, so that nothing larger is
	 * read to look at its beginning.
	 */
	default byte[] head(ProjectPath path, int count) {
		return read(path, count) instanceof Content content
				? content.bytes()
				: null;
	}

	/**
	 * List a folder, the project root when folder is null: its entries,
	 * why it could not be listed, or null when there is no such folder.
	 */
	default Listing list(ProjectPath folder) {
		return new Failed("this storage cannot list folders");
	}

	/**
	 * Return the changes the file or folder at path, the root when null,
	 * offers; none when there is no such file or folder. A change it
	 * does not offer is refused; one it offers can still fail.
	 */
	default Offers offers(ProjectPath path) {
		return Offers.NONE;
	}

	/**
	 * Forget what listings told about the folders, so that the next call
	 * looks up every path in storage again, to see changes made
	 * elsewhere. A store that keeps nothing does nothing.
	 */
	default void forgetListings() {
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
	 * Write the bytes source produces as
	 * {@link #write(ProjectPath, byte[], String)} writes bytes. A store
	 * that cannot stream collects them first; a source that throws
	 * writes nothing and is refused as {@link Refusal#FAILED}.
	 */
	default Change write(ProjectPath path, Source source,
			String expectedRevision) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			source.writeTo(out);
		} catch (IOException e) {
			return new Refused(Refusal.FAILED, e.getMessage());
		}
		return write(path, out.toByteArray(), expectedRevision);
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
