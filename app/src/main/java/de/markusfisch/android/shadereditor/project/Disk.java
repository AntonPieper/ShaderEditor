package de.markusfisch.android.shadereditor.project;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/**
 * The operations on files that a session is made of, its journals,
 * undo histories and what it remembers, each one a single step that
 * fails or is done, so that a test can fail any of them, or end the
 * process after it, without anyone remembering to. Nothing here
 * decides what may be written over or deleted: that is
 * {@link Journal}'s, in one place. No file is ever cut: what a file
 * holds changes only by appending to it or by another file taking its
 * name.
 */
interface Disk {
	/**
	 * Return the first bytes of file, at most max: all the file has up
	 * to there, never fewer. It throws when the file cannot be read
	 * now, also when it gave fewer bytes than it has, which says
	 * nothing about what it holds.
	 */
	byte[] read(File file, long max) throws IOException;

	/**
	 * Return what {@link #read} gives, and throw when those are fewer
	 * bytes than file has up to max: bytes that were not read are not
	 * missing from the file, and nothing may be concluded from them.
	 */
	static byte[] whole(Disk disk, File file, long max) throws IOException {
		long expected = Math.min(file.length(), max);
		byte[] bytes = disk.read(file, max);
		if (bytes.length < expected || file.length() < expected) {
			throw new IOException(file.getName() + ": read " +
					bytes.length + " of " + expected + " bytes");
		}
		return bytes;
	}

	/** Return the files in dir, or null when it cannot be listed. */
	File[] list(File dir);

	/** Make a new, empty file in dir, and dir when it is missing. */
	File temp(File dir, String prefix, String suffix) throws IOException;

	/**
	 * Write what source writes to file, after what it holds when
	 * append, else in its place. It is not durable before {@link #sync}.
	 */
	void write(File file, ProjectFiles.Source source, boolean append)
			throws IOException;

	/** Make what file holds durable. */
	void sync(File file) throws IOException;

	/** Give from the name to, in place of what has it; false if not. */
	boolean rename(File from, File to);

	/** Remove file; return whether it is gone now. */
	boolean delete(File file);

	/** The files of the device. */
	Disk REAL = new Disk() {
		@Override
		public byte[] read(File file, long max) throws IOException {
			long length = Math.min(file.length(), max);
			if (length > Integer.MAX_VALUE - 8) {
				throw new IOException("too large");
			}
			byte[] bytes = new byte[(int) length];
			try (FileInputStream in = new FileInputStream(file)) {
				int read = 0;
				for (int n; read < bytes.length && (n = in.read(bytes, read,
						bytes.length - read)) > 0; ) {
					read += n;
				}
				if (read != bytes.length) {
					// Not what the file holds: a part of it.
					throw new IOException(file.getName() + ": read " +
							read + " of " + bytes.length + " bytes");
				}
				return bytes;
			}
		}

		@Override
		public File[] list(File dir) {
			return dir.listFiles();
		}

		@Override
		public File temp(File dir, String prefix, String suffix)
				throws IOException {
			if (!dir.isDirectory() && !dir.mkdirs()) {
				throw new IOException("no folder");
			}
			return File.createTempFile(prefix, suffix, dir);
		}

		@Override
		public void write(File file, ProjectFiles.Source source,
				boolean append) throws IOException {
			try (FileOutputStream stream = new FileOutputStream(file,
					append)) {
				OutputStream out = new BufferedOutputStream(stream);
				source.writeTo(out);
				out.flush();
			}
		}

		@Override
		public void sync(File file) throws IOException {
			try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
				raf.getFD().sync();
			}
		}

		@Override
		public boolean rename(File from, File to) {
			return from.renameTo(to);
		}

		@Override
		public boolean delete(File file) {
			return !file.exists() || file.delete();
		}
	};
}
