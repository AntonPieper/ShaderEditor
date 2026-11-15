package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import de.markusfisch.android.shadereditor.project.LibraryArchive.Facts;
import de.markusfisch.android.shadereditor.project.LibraryArchive.Limits;
import de.markusfisch.android.shadereditor.project.LibraryArchive.Refused;

/**
 * Writes the library as an archive, see {@link LibraryArchive}: every
 * file and folder of it that has no dot name, in the order of the
 * paths, each streamed, so that memory does not grow with the library.
 * The same library gives the same bytes, but for the time of the
 * export. Use it on the queue the projects work on.
 */
final class LibraryExport {
	/**
	 * What was written: how many files, and how many files and folders
	 * with a dot name were left out.
	 */
	record Written(int files, int hidden) {
	}

	private final DirectoryProjectFiles files;
	// The paths of the files, and of the empty folders with a slash.
	private final List<String> paths = new ArrayList<>();
	private int hidden;

	private LibraryExport(DirectoryProjectFiles files) {
		this.files = files;
	}

	/**
	 * Write the library of files to out, which is not closed, with what
	 * settings keeps of the files written.
	 *
	 * @param app  the version of the app
	 * @param time the time of the export
	 * @throws Refused     when the library cannot be an archive
	 * @throws IOException when out cannot be written
	 */
	static Written write(DirectoryProjectFiles files,
			LibrarySettings.State settings, String app, long time,
			Limits limits, OutputStream out) throws Refused, IOException {
		LibraryExport export = new LibraryExport(files);
		export.list(null, new LibraryArchive.Names(), limits);
		if (export.paths.isEmpty()) {
			throw new Refused("the library holds no files");
		}
		Collections.sort(export.paths);
		ZipOutputStream zip = new ZipOutputStream(out);
		put(zip, LibraryArchive.FACTS, time);
		zip.write(LibraryArchive.text(app, time,
				export.facts(settings)));
		long left = limits.bytes();
		byte[] buffer = new byte[8192];
		int written = 0;
		for (String path : export.paths) {
			if (path.endsWith("/")) {
				put(zip, path, export.file(path.substring(0,
						path.length() - 1)).lastModified());
				continue;
			}
			File file = export.file(path);
			put(zip, path, file.lastModified());
			try (InputStream in = new FileInputStream(file)) {
				for (int n; (n = in.read(buffer)) > -1; ) {
					if ((left -= n) < 0) {
						throw new LibraryArchive.TooLarge(limits);
					}
					zip.write(buffer, 0, n);
				}
			} catch (IOException e) {
				throw new Refused(path + " cannot be read");
			}
			++written;
		}
		zip.finish();
		return new Written(written, export.hidden);
	}

	private static void put(ZipOutputStream zip, String name, long time)
			throws IOException {
		ZipEntry entry = new ZipEntry(name);
		entry.setTime(time);
		zip.putNextEntry(entry);
	}

	// What is kept of the files that are written.
	private Facts facts(LibrarySettings.State settings) {
		Map<String, Float> qualities = new HashMap<>();
		for (Map.Entry<String, Float> quality :
				settings.qualities().entrySet()) {
			if (Collections.binarySearch(paths, quality.getKey()) > -1) {
				qualities.put(quality.getKey(), quality.getValue());
			}
		}
		ProjectPath template = settings.template();
		return new Facts(template != null && Collections.binarySearch(
				paths, template.toString()) > -1
				? template.toString()
				: null, qualities);
	}

	private void list(ProjectPath folder, LibraryArchive.Names names,
			Limits limits) throws Refused {
		if (!(files.list(folder) instanceof ProjectFiles.Entries entries)) {
			throw new Refused((folder != null ? folder : "the library") +
					" cannot be listed");
		}
		String prefix = folder != null ? folder + "/" : "";
		boolean empty = true;
		for (ProjectFiles.Entry entry : entries.entries()) {
			String name = prefix + entry.name();
			if (entry.name().startsWith(".")) {
				++hidden;
				continue;
			}
			empty = false;
			if (entry.folder()) {
				names.folder(name);
				list(ProjectPath.parse(name).value(), names, limits);
			} else {
				names.file(name);
				add(name, limits);
			}
		}
		if (empty && folder != null) {
			add(prefix, limits);
		}
	}

	private void add(String path, Limits limits) throws Refused {
		if (paths.size() >= limits.entries() - 1) {
			throw new Refused("it holds more than " + limits.entries() +
					" files");
		}
		paths.add(path);
	}

	private File file(String path) throws Refused {
		File file;
		try {
			file = files.find(ProjectPath.parse(path).value());
		} catch (IOException e) {
			file = null;
		}
		if (file == null) {
			throw new Refused(path + " cannot be read");
		}
		return file;
	}
}
