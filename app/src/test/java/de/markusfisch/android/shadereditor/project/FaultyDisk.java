package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.IOException;

/**
 * The files of the device, where a test makes one operation fail, or
 * the process die after one: every read, list, write, sync, cut, rename
 * and deletion of a journal or history goes through here, so each can
 * be failed without a test naming it.
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
		FAIL_THEN_DIE
	}

	/** The process died: thrown by every operation from then on. */
	static final class Death extends Error {
	}

	// How many operations were asked for while counting; which of them
	// the fault is for; whether it happened.
	private int operations;
	private boolean counting;
	private Fault fault = Fault.NONE;
	private int at;
	private boolean happened;
	private boolean dead;
	// Every read fails, as without the permission to read the files.
	boolean denied;

	/** Count operations from now on; number at meets fault. */
	void arm(Fault fault, int at) {
		this.fault = fault;
		this.at = at;
		operations = 0;
		counting = true;
		happened = false;
		dead = false;
	}

	/** Stop counting and failing; return how many operations there were. */
	int disarm() {
		counting = false;
		dead = false;
		fault = Fault.NONE;
		return operations;
	}

	/** Forget what happened, for the next sequence. */
	void reset() {
		disarm();
		happened = false;
		denied = false;
	}

	/** Return whether an operation failed or the process died. */
	boolean happened() {
		return happened || denied;
	}

	// Count an operation; return whether it fails.
	private boolean fails() {
		if (dead) {
			throw new Death();
		} else if (!counting) {
			return false;
		}
		++operations;
		boolean fails = operations == at &&
				(fault == Fault.FAIL || fault == Fault.FAIL_THEN_DIE);
		happened |= fails;
		return fails;
	}

	// The operation counted last was done: the process may die now.
	private void done() {
		if (counting && (fault == Fault.DIE && operations == at ||
				fault == Fault.FAIL_THEN_DIE && operations == at + 1)) {
			happened = true;
			dead = true;
			throw new Death();
		}
	}

	@Override
	public byte[] read(File file, long max) throws IOException {
		if (fails() || denied) {
			throw new IOException(file.getName() + ": Permission denied");
		}
		byte[] bytes = REAL.read(file, max);
		done();
		return bytes;
	}

	@Override
	public File[] list(File dir) {
		if (fails()) {
			return null;
		}
		File[] files = REAL.list(dir);
		done();
		return files;
	}

	@Override
	public File temp(File dir, String prefix, String suffix)
			throws IOException {
		if (fails()) {
			throw new IOException("No space left on device");
		}
		File temp = REAL.temp(dir, prefix, suffix);
		done();
		return temp;
	}

	@Override
	public void write(File file, ProjectFiles.Source source, boolean append)
			throws IOException {
		if (fails()) {
			throw new IOException("No space left on device");
		}
		REAL.write(file, source, append);
		done();
	}

	@Override
	public void sync(File file) throws IOException {
		if (fails()) {
			throw new IOException("I/O error");
		}
		REAL.sync(file);
		done();
	}

	@Override
	public void truncate(File file, long length) throws IOException {
		if (fails()) {
			throw new IOException("I/O error");
		}
		REAL.truncate(file, length);
		done();
	}

	@Override
	public boolean rename(File from, File to) {
		if (fails()) {
			return false;
		}
		boolean renamed = REAL.rename(from, to);
		done();
		return renamed;
	}

	@Override
	public boolean delete(File file) {
		if (fails()) {
			return false;
		}
		boolean deleted = REAL.delete(file);
		done();
		return deleted;
	}
}
