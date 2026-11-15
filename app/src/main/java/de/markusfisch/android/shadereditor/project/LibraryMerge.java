package de.markusfisch.android.shadereditor.project;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.LibraryArchive.Refused;
import de.markusfisch.android.shadereditor.project.LibraryImport.Staged;

/**
 * Adds the files of a checked archive to the library. It only adds:
 * no file is written over and none is deleted. A shader whose name
 * is taken comes in beside it under the next free name, a shader of
 * several files, a folder that holds main.frag, as a whole; any other
 * file the library has already stays as it is, see {@link Library#copy}.
 * A shader appears whole, and when one write is refused everything
 * this import made is removed again. Use it on the queue the projects
 * work on.
 */
final class LibraryMerge {
	/**
	 * What an import did: how many shaders and other files it made, how
	 * many were there already, what came in under another name, by its
	 * path in the archive, and what was kept as it is. With a problem
	 * nothing was imported. unkept: the files are there, their
	 * qualities could not be kept.
	 */
	record Result(int shaders, int files, int there,
			Map<ProjectPath, ProjectPath> renamed, List<ProjectPath> kept,
			boolean unkept, String problem) {
	}

	// Where a shader of several files is written before it appears.
	private static final String UNPACKING = ".shadereditor-import";

	private final DirectoryProjectFiles files;
	private final LibraryMove.Stamp stamp;
	// The names of each folder, with those the archive has in it, and
	// as the library had them.
	private final Map<String, Naming> names = new HashMap<>();
	private final Map<String, List<String>> library = new HashMap<>();
	private final Map<ProjectPath, ProjectPath> renamed =
			new LinkedHashMap<>();
	private final List<ProjectPath> kept = new ArrayList<>();
	// What this import made, in its order: files, and folders with all
	// that is in them; and the folders it made above them.
	private final List<ProjectPath> made = new ArrayList<>();
	private final List<ProjectPath> folders = new ArrayList<>();
	// Where each file that was made is, by its path in the archive.
	private final Map<String, ProjectPath> placed = new LinkedHashMap<>();
	private int shaders;
	private int shared;
	private int there;

	private LibraryMerge(DirectoryProjectFiles files,
			LibraryMove.Stamp stamp) {
		this.files = files;
		this.stamp = stamp;
	}

	static Result place(DirectoryProjectFiles files,
			LibraryImport.Archive archive, LibrarySettings settings,
			LibraryMove.Stamp stamp) {
		LibraryMerge merge = new LibraryMerge(files, stamp);
		ProjectPath unpacking = path(UNPACKING);
		String problem;
		try {
			merge.remove(unpacking);
			merge.add(archive);
			problem = null;
		} catch (Refused e) {
			problem = e.getMessage() + merge.undo();
		}
		files.delete(unpacking);
		if (problem != null) {
			return new Result(0, 0, 0, Map.of(), List.of(), false, problem);
		}
		Map<ProjectPath, Float> qualities = new LinkedHashMap<>();
		for (Map.Entry<String, ProjectPath> file : merge.placed.entrySet()) {
			Float quality = archive.facts().qualities().get(file.getKey());
			qualities.put(file.getValue(), quality != null ? quality : 1f);
		}
		String template = archive.facts().template();
		boolean unkept = !qualities.isEmpty() && !settings.adopt(qualities,
				template != null ? merge.placed.get(template) : null);
		return new Result(merge.shaders, merge.shared, merge.there,
				merge.renamed, merge.kept, unkept, null);
	}

	private void add(LibraryImport.Archive archive) throws Refused {
		// A name of the archive is never given to another of its files.
		for (Staged staged : archive.files()) {
			take(staged.path());
		}
		for (ProjectPath folder : archive.folders()) {
			take(folder);
		}
		// The outermost folders that hold main.frag, each one shader.
		List<String> wholes = new ArrayList<>();
		for (Staged staged : archive.files()) {
			String folder = parent(staged.path().toString());
			if (!folder.isEmpty() && staged.path().getFileName().equals(
					OpenProject.MAIN.toString())) {
				for (int i = wholes.size(); i-- > 0; ) {
					if (wholes.get(i).startsWith(folder + "/")) {
						wholes.remove(i);
					}
				}
				if (whole(wholes, folder + "/") == null) {
					wholes.add(folder);
				}
			}
		}
		Map<String, List<Staged>> inside = new LinkedHashMap<>();
		for (String folder : wholes) {
			inside.put(folder, new ArrayList<>());
		}
		for (Staged staged : archive.files()) {
			String folder = whole(wholes, staged.path().toString());
			if (folder != null) {
				inside.get(folder).add(staged);
			} else {
				file(staged);
			}
		}
		int number = 0;
		for (Map.Entry<String, List<Staged>> folder : inside.entrySet()) {
			folder(folder.getKey(), folder.getValue(), archive.folders(),
					UNPACKING + "/" + number++);
		}
		for (ProjectPath folder : archive.folders()) {
			if (whole(wholes, folder + "/") == null && find(folder) == null &&
					!variant(folder)) {
				above(folder + "/-");
				check(files.createFolder(folder), folder);
			}
		}
	}

	// A file that is no part of a shader of several files.
	private void file(Staged staged) throws Refused {
		ProjectPath path = staged.path();
		File found = find(path);
		boolean shader = ShaderLanguage.namesShader(path.getFileName());
		if (found != null && found.isFile() && same(found, staged.file())) {
			++there;
			return;
		} else if (found != null || variant(path)) {
			if (!shader) {
				kept.add(path);
				return;
			}
			String name = path.getFileName();
			int dot = name.lastIndexOf('.');
			ProjectPath free = unused(path, name.substring(0, dot),
					name.substring(dot));
			renamed.put(path, free);
			path = free;
		}
		above(path.toString());
		write(path, staged);
		made.add(path);
		placed.put(staged.path().toString(), path);
		if (shader) {
			++shaders;
		} else {
			++shared;
		}
	}

	// A shader of several files: there as it is, or made as a whole.
	private void folder(String folder, List<Staged> inside,
			List<ProjectPath> empty, String unpacking) throws Refused {
		ProjectPath path = path(folder);
		File found = find(path);
		boolean same = found != null && found.isDirectory();
		for (int i = 0; same && i < inside.size(); ++i) {
			File file = find(inside.get(i).path());
			same = file != null && file.isFile() &&
					same(file, inside.get(i).file());
		}
		if (same) {
			++there;
			return;
		} else if (found != null || variant(path)) {
			ProjectPath free = unused(path, path.getFileName(), "");
			renamed.put(path, free);
			path = free;
		}
		for (Staged staged : inside) {
			write(path(unpacking + staged.path().toString()
					.substring(folder.length())), staged);
		}
		for (ProjectPath inner : empty) {
			if (inner.toString().startsWith(folder + "/")) {
				check(files.createFolder(path(unpacking + inner.toString()
						.substring(folder.length()))), inner);
			}
		}
		above(path.toString());
		check(files.rename(path(unpacking), path), path);
		made.add(path);
		for (Staged staged : inside) {
			placed.put(staged.path().toString(), path(path + staged.path()
					.toString().substring(folder.length())));
		}
		++shaders;
	}

	private void write(ProjectPath path, Staged staged) throws Refused {
		check(files.write(path, out -> {
			try (InputStream in = new FileInputStream(staged.file())) {
				byte[] buffer = new byte[8192];
				for (int n; (n = in.read(buffer)) > -1; ) {
					out.write(buffer, 0, n);
				}
			}
		}, null), staged.path());
		if (staged.modified() > 0) {
			stamp.modified(path, staged.modified());
		}
	}

	private static void check(ProjectFiles.Change change, ProjectPath path)
			throws Refused {
		if (change instanceof ProjectFiles.Refused refused) {
			throw new Refused(path + " cannot be written: " +
					refused.reason());
		}
	}

	// Note the folders above path that are not there yet: the write
	// makes them, and they are this import's.
	private void above(String path) throws Refused {
		for (int slash = path.indexOf('/'); slash > -1;
				slash = path.indexOf('/', slash + 1)) {
			ProjectPath folder = path(path.substring(0, slash));
			if (!folders.contains(folder) && find(folder) == null) {
				folders.add(folder);
			}
		}
	}

	// Remove what this import made; return what could not be removed.
	private String undo() {
		List<ProjectPath> left = new ArrayList<>();
		for (int i = made.size(); i-- > 0; ) {
			if (files.delete(made.get(i)) instanceof ProjectFiles.Refused) {
				left.add(made.get(i));
			}
		}
		// Deepest first; one that holds something else now stays.
		for (int i = folders.size(); i-- > 0; ) {
			if (files.list(folders.get(i)) instanceof
					ProjectFiles.Entries entries &&
					entries.entries().isEmpty()) {
				files.delete(folders.get(i));
			}
		}
		return left.isEmpty() ? "" : "; " + left + " could not be removed";
	}

	private void remove(ProjectPath path) throws Refused {
		if (find(path) != null &&
				files.delete(path) instanceof ProjectFiles.Refused refused) {
			throw new Refused(path + " cannot be removed: " +
					refused.reason());
		}
	}

	private File find(ProjectPath path) throws Refused {
		try {
			return files.find(path);
		} catch (IOException e) {
			throw new Refused(path + " of the library cannot be read: " +
					e.getMessage());
		}
	}

	// Whether the library has path under a name that differs only by
	// case: in the last part a reason for another name, above it a
	// folder a phone cannot keep apart from the archive's.
	private boolean variant(ProjectPath path) throws Refused {
		String text = path.toString();
		for (int end = text.indexOf('/'); ; end = text.indexOf('/', end + 1)) {
			String part = end > -1 ? text.substring(0, end) : text;
			if (find(path(part)) == null) {
				if (new Naming(listed(parent(part))).check(part.substring(
						part.lastIndexOf('/') + 1)) != Naming.Problem.TAKEN) {
					return false;
				} else if (end < 0) {
					return true;
				}
				throw new Refused("the library has " + part + " under a " +
						"name that differs only by case, which a phone " +
						"cannot keep apart");
			} else if (end < 0) {
				return false;
			}
		}
	}

	private ProjectPath unused(ProjectPath path, String stem,
			String ending) throws Refused {
		String folder = parent(path.toString());
		Naming naming = names(folder);
		String name = naming.unused(stem, ending);
		naming.take(name);
		return path(folder.isEmpty() ? name : folder + "/" + name);
	}

	private void take(ProjectPath path) throws Refused {
		String text = path.toString();
		for (int end = text.indexOf('/'); ; end = text.indexOf('/', end + 1)) {
			String part = end > -1 ? text.substring(0, end) : text;
			names(parent(part)).take(part.substring(
					part.lastIndexOf('/') + 1));
			if (end < 0) {
				return;
			}
		}
	}

	// The names that are taken in folder, "" for the root: those the
	// library had, and those of the archive.
	private Naming names(String folder) throws Refused {
		Naming naming = names.get(folder);
		if (naming == null) {
			naming = new Naming(listed(folder));
			names.put(folder, naming);
		}
		return naming;
	}

	// The names the library had in folder before this import.
	private List<String> listed(String folder) throws Refused {
		List<String> listed = library.get(folder);
		if (listed == null) {
			listed = new ArrayList<>();
			ProjectFiles.Listing listing = files.list(folder.isEmpty()
					? null
					: path(folder));
			if (listing instanceof ProjectFiles.Entries entries) {
				for (ProjectFiles.Entry entry : entries.entries()) {
					listed.add(entry.name());
				}
			} else if (listing != null) {
				throw new Refused((folder.isEmpty() ? "the library"
						: folder) + " cannot be listed");
			}
			library.put(folder, listed);
		}
		return listed;
	}

	// The folder of wholes that path is in, or null.
	private static String whole(List<String> wholes, String path) {
		for (String folder : wholes) {
			if (path.startsWith(folder + "/")) {
				return folder;
			}
		}
		return null;
	}

	private static String parent(String path) {
		int slash = path.lastIndexOf('/');
		return slash > -1 ? path.substring(0, slash) : "";
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	private static boolean same(File a, File b) {
		if (a.length() != b.length()) {
			return false;
		}
		try (InputStream one = new BufferedInputStream(
				new FileInputStream(a));
				InputStream other = new BufferedInputStream(
						new FileInputStream(b))) {
			for (int c; (c = one.read()) == other.read(); ) {
				if (c < 0) {
					return true;
				}
			}
			return false;
		} catch (IOException e) {
			return false;
		}
	}
}
