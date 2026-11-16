package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The files of the device, where a test makes an operation fail, go on
 * failing, give fewer bytes than the file has, or the process die after
 * one: every read, list, write, sync, rename and deletion of a file of
 * a session goes through here, so each can be failed without a test
 * naming it.
 */
final class FaultyDisk implements Disk {
	/** What becomes of the operation numbered {@link #at}. */
	enum Fault {
		/** Nothing: operations are only counted. */
		NONE,
		/** It fails, once. */
		FAIL,
		/** It is done, and the process dies. */
		DIE,
		/** It fails, the next one is done, and the process dies. */
		FAIL_THEN_DIE,
		/**
		 * It fails, and so does every later operation of its kind, as
		 * when no space is left or a permission is gone, until
		 * {@link #heal}.
		 */
		STUCK,
		/**
		 * A read: it gives only the first whole parts of the file and
		 * says nothing. Any other operation is done.
		 */
		SHORT
	}

	/** The kinds of operations; one that is stuck fails all of its kind. */
	enum Kind {
		READ, LIST, WRITE, SYNC, RENAME, DELETE
	}

	/** The process died: thrown by every operation from then on. */
	static final class Death extends Error {
	}

	// How many operations were asked for while counting, and their
	// kinds; which of them the fault is for; whether it happened.
	private final List<Kind> kinds = new ArrayList<>();
	private boolean counting;
	private Fault fault = Fault.NONE;
	private int at;
	private boolean happened;
	private boolean dead;
	private Kind stuck;
	// Every read fails, as without the permission to read the files.
	boolean denied;
	// What fails for as long as a test says: every listing, every
	// write (as when no space is left), every rename, every deletion.
	boolean unlisted;
	boolean full;
	boolean unrenamed;
	boolean undeleted;
	// Every read gives only the first whole parts of a file; and every
	// read only this many bytes of a longer file, -1 for all of it.
	boolean shortened;
	int cut = -1;

	/** Count operations from now on; number at meets fault. */
	void arm(Fault fault, int at) {
		this.fault = fault;
		this.at = at;
		kinds.clear();
		counting = true;
		happened = false;
		dead = false;
		stuck = null;
	}

	/** Stop counting and failing; return how many operations there were. */
	int disarm() {
		counting = false;
		dead = false;
		stuck = null;
		fault = Fault.NONE;
		return kinds.size();
	}

	/** Return the kind of each operation counted, in order. */
	List<Kind> kinds() {
		return new ArrayList<>(kinds);
	}

	/** Return whether a kind of operation is failing until healed. */
	boolean stuck() {
		return stuck != null;
	}

	/** What was stuck works again. */
	void heal() {
		stuck = null;
	}

	/** Forget what happened, for the next sequence. */
	void reset() {
		disarm();
		happened = false;
		denied = unlisted = full = unrenamed = undeleted = false;
		shortened = false;
		cut = -1;
	}

	/** Return whether an operation failed or the process died. */
	boolean happened() {
		return happened || denied || unlisted || full || unrenamed ||
				undeleted || shortened || cut >= 0;
	}

	// Count an operation of kind; return whether it fails.
	private boolean fails(Kind kind, boolean always) {
		if (dead) {
			throw new Death();
		}
		boolean fails = always || kind == stuck;
		if (counting) {
			kinds.add(kind);
			if (kinds.size() == at && (fault == Fault.FAIL ||
					fault == Fault.FAIL_THEN_DIE || fault == Fault.STUCK)) {
				fails = true;
				if (fault == Fault.STUCK) {
					stuck = kind;
				}
			}
		}
		happened |= fails;
		return fails;
	}

	// The operation counted last was done: the process may die now.
	private void done() {
		if (counting && (fault == Fault.DIE && kinds.size() == at ||
				fault == Fault.FAIL_THEN_DIE && kinds.size() == at + 1)) {
			happened = true;
			dead = true;
			throw new Death();
		}
	}

	@Override
	public byte[] read(File file, long max) throws IOException {
		if (fails(Kind.READ, denied)) {
			throw new IOException(file.getName() + ": Permission denied");
		}
		byte[] bytes = REAL.read(file, max);
		boolean part = shortened ||
				counting && fault == Fault.SHORT && kinds.size() == at;
		done();
		if (part && bytes.length > 0) {
			happened = true;
			return parts(bytes);
		}
		return cut >= 0 && cut < bytes.length
				? Arrays.copyOf(bytes, cut)
				: bytes;
	}

	// Return the first whole parts of bytes, at least one fewer than
	// there are: of a journal, which is four bytes and then parts of a
	// length, as many bytes and a checksum, that is a file that ends
	// where an older one could have ended. Of anything else, half.
	private static byte[] parts(byte[] bytes) {
		int end = 4;
		int last = 0;
		while (end + 8 <= bytes.length) {
			int length = (bytes[end] & 0xff) << 24 |
					(bytes[end + 1] & 0xff) << 16 |
					(bytes[end + 2] & 0xff) << 8 | bytes[end + 3] & 0xff;
			if (length < 0 || end + 8L + length > bytes.length) {
				break;
			}
			last = end;
			end += 8 + length;
		}
		int kept = end < bytes.length ? end : last;
		return Arrays.copyOf(bytes, kept > 4 ? kept : bytes.length / 2);
	}

	@Override
	public File[] list(File dir) {
		if (fails(Kind.LIST, unlisted)) {
			return null;
		}
		File[] files = REAL.list(dir);
		done();
		return files;
	}

	@Override
	public File temp(File dir, String prefix, String suffix)
			throws IOException {
		if (fails(Kind.WRITE, full)) {
			throw new IOException("No space left on device");
		}
		File temp = REAL.temp(dir, prefix, suffix);
		done();
		return temp;
	}

	@Override
	public void write(File file, ProjectFiles.Source source, boolean append)
			throws IOException {
		if (fails(Kind.WRITE, full)) {
			throw new IOException("No space left on device");
		}
		REAL.write(file, source, append);
		done();
	}

	@Override
	public void sync(File file) throws IOException {
		if (fails(Kind.SYNC, false)) {
			throw new IOException("I/O error");
		}
		REAL.sync(file);
		done();
	}

	@Override
	public boolean rename(File from, File to) {
		if (fails(Kind.RENAME, unrenamed)) {
			return false;
		}
		boolean renamed = REAL.rename(from, to);
		done();
		return renamed;
	}

	@Override
	public boolean delete(File file) {
		if (fails(Kind.DELETE, undeleted)) {
			return false;
		}
		boolean deleted = REAL.delete(file);
		done();
		return deleted;
	}
}
