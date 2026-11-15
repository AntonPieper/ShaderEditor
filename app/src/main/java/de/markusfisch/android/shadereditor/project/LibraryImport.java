package de.markusfisch.android.shadereditor.project;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.markusfisch.android.shadereditor.project.LibraryArchive.Facts;
import de.markusfisch.android.shadereditor.project.LibraryArchive.Limits;
import de.markusfisch.android.shadereditor.project.LibraryArchive.Refused;

/**
 * Reads an archive of the library, see {@link LibraryArchive}, from
 * anywhere, without touching the library: the whole of it is checked,
 * and its files are written to numbered files of a staging folder,
 * never under a name the archive gives. It is read once as a stream;
 * sizes are counted as the bytes come, and nothing the archive says
 * of itself is believed. One entry that cannot be a file of a library
 * refuses the archive.
 */
final class LibraryImport {
	/** A file of the archive: where it goes, and where its bytes are. */
	record Staged(ProjectPath path, File file, long modified) {
	}

	/**
	 * A checked archive: its files in their order, the folders it has
	 * that hold nothing, what its text file says, and how many entries
	 * with a dot name were skipped.
	 */
	record Archive(List<Staged> files, List<ProjectPath> folders,
			Facts facts, int hidden) {
	}

	// The end record of a zip without its comment, and with the longest.
	private static final int END = 22;
	private static final int END_SPAN = END + 0xffff;

	private final File staging;
	private final Limits limits;
	private final byte[] buffer = new byte[8192];
	private long left;

	private LibraryImport(File staging, Limits limits) {
		this.staging = staging;
		this.limits = limits;
		left = limits.bytes();
	}

	/**
	 * Read the archive in, which is not closed, into staging, a folder
	 * of its own that the caller removes.
	 *
	 * @throws Refused when it is not an archive a library can take;
	 *                 what is in staging then is of no use
	 */
	static Archive read(InputStream in, File staging, Limits limits)
			throws Refused {
		return new LibraryImport(staging, limits).read(in);
	}

	private Archive read(InputStream in) throws Refused {
		if (!staging.isDirectory() && !staging.mkdirs()) {
			throw new Refused("there is no room to unpack it");
		}
		LibraryArchive.Names names = new LibraryArchive.Names();
		List<Staged> files = new ArrayList<>();
		Facts facts = Facts.NONE;
		int entries = 0;
		int hidden = 0;
		Tail tail = new Tail(in);
		try {
			ZipInputStream zip = new ZipInputStream(tail);
			for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
				if (++entries > limits.entries()) {
					throw new Refused("it holds more than " +
							limits.entries() + " files");
				}
				String name = entry.getName();
				if (entry.isDirectory()) {
					names.folder(name);
					copy(zip, null);
					continue;
				}
				ProjectPath path = names.file(name);
				if (name.equals(LibraryArchive.FACTS)) {
					facts = LibraryArchive.facts(text(zip));
				} else if (path == null) {
					++hidden;
					copy(zip, null);
				} else {
					File file = new File(staging, String.valueOf(
							files.size()));
					stage(zip, file);
					files.add(new Staged(path, file,
							Math.max(0, entry.getTime())));
				}
			}
			// What follows the entries is the zip's list of them.
			copy(tail, null);
		} catch (IOException | IllegalArgumentException e) {
			// A name that is not UTF-8 is an illegal argument. Android
			// 14 and later refuse a name with ".." or a leading slash
			// here themselves, before the names are checked.
			throw new Refused("it cannot be read as a zip file" +
					(e.getMessage() != null ? " (" + e.getMessage() + ")"
							: ""));
		}
		if (entries == 0) {
			throw new Refused("it is not a zip file, or it is empty");
		} else if (tail.entries() != entries) {
			throw new Refused("it is cut off or damaged: its end does " +
					"not list the " + entries + " entries that were read");
		}
		List<ProjectPath> folders = names.empty();
		if (files.isEmpty() && folders.isEmpty()) {
			throw new Refused("it holds no files");
		}
		return new Archive(files, folders, facts, hidden);
	}

	private void stage(InputStream in, File file)
			throws IOException, Refused {
		OutputStream out;
		try {
			out = new FileOutputStream(file);
		} catch (IOException e) {
			throw new Refused("there is no room to unpack it");
		}
		try (out) {
			copy(in, new OutputStream() {
				@Override
				public void write(int b) throws IOException {
					write(new byte[]{(byte) b}, 0, 1);
				}

				// Not an error of the archive.
				@Override
				public void write(byte[] bytes, int offset, int count)
						throws IOException {
					try {
						out.write(bytes, offset, count);
					} catch (IOException e) {
						throw new Full();
					}
				}
			});
		} catch (Full e) {
			throw new Refused("there is no room to unpack it");
		}
	}

	// Read in to its end, into out when there is one; every byte counts.
	private void copy(InputStream in, OutputStream out)
			throws IOException, Refused {
		for (int n; (n = in.read(buffer)) > -1; ) {
			if ((left -= n) < 0) {
				throw new LibraryArchive.TooLarge(limits);
			}
			if (out != null) {
				out.write(buffer, 0, n);
			}
		}
	}

	// The text file, which is small or not the app's.
	private static byte[] text(InputStream in) throws IOException, Refused {
		ByteArrayOutputStream text = new ByteArrayOutputStream();
		byte[] bytes = new byte[8192];
		for (int n; (n = in.read(bytes)) > -1; ) {
			text.write(bytes, 0, n);
			if (text.size() > LibraryArchive.FACTS_BYTES) {
				throw new Refused(LibraryArchive.FACTS + " is too large");
			}
		}
		return text.toByteArray();
	}

	private static final class Full extends IOException {
	}

	// Remembers the last bytes that were read, where the end record of
	// a zip is, whoever reads them.
	private static final class Tail extends FilterInputStream {
		private final byte[] last = new byte[END_SPAN];
		private long count;

		private Tail(InputStream in) {
			super(in);
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			return read(one, 0, 1) < 1 ? -1 : one[0] & 0xff;
		}

		@Override
		public int read(byte[] bytes, int offset, int length)
				throws IOException {
			int n = in.read(bytes, offset, length);
			for (int i = Math.max(0, n - last.length); i < n; ++i) {
				last[(int) ((count + i) % last.length)] = bytes[offset + i];
			}
			count += Math.max(0, n);
			return n;
		}

		@Override
		public long skip(long n) throws IOException {
			byte[] skipped = new byte[(int) Math.min(n, 8192)];
			return Math.max(0, read(skipped, 0, skipped.length));
		}

		@Override
		public boolean markSupported() {
			return false;
		}

		// How many entries the end record lists, -1 without one: the
		// last one that ends where the bytes end.
		private int entries() {
			int length = (int) Math.min(count, last.length);
			for (int i = length - END; i >= 0; --i) {
				if (at(length, i) == 0x50 && at(length, i + 1) == 0x4b &&
						at(length, i + 2) == 5 && at(length, i + 3) == 6 &&
						i + END + (at(length, i + 20) |
								at(length, i + 21) << 8) == length) {
					return at(length, i + 10) | at(length, i + 11) << 8;
				}
			}
			return -1;
		}

		// The byte at index of the last length bytes.
		private int at(int length, int index) {
			return last[(int) ((count - length + index) % last.length)] &
					0xff;
		}
	}
}
