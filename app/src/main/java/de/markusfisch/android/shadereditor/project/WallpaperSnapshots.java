package de.markusfisch.android.shadereditor.project;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * What the live wallpaper shows when it was set from a file: a copy
 * of the files the shader was made of then, in the app's own storage,
 * so that it does not depend on where they came from, or on what
 * became of them since. One snapshot is installed at a time. The one
 * before it stays until the next is installed after that, so that a
 * reader that had just been told of it still finds its files; a
 * reader that finds them gone reads {@link #current} again. Use it
 * off the main thread.
 */
public final class WallpaperSnapshots {
	/**
	 * The installed snapshot: its folder, the file that runs, the
	 * quality it runs at, and the file of the library it was taken of,
	 * or null when it was taken elsewhere.
	 */
	public record Snapshot(File folder, ProjectPath entry, float quality,
			ProjectPath origin) {
		public ProjectFiles files() {
			return new DirectoryProjectFiles(folder);
		}
	}

	private static final String CURRENT = "current";

	private final File root;

	public WallpaperSnapshots(File root) {
		this.root = root;
	}

	/** Return the snapshots of an app whose files folder is filesDir. */
	public static WallpaperSnapshots of(File filesDir) {
		return new WallpaperSnapshots(new File(filesDir, "wallpaper"));
	}

	/** Return the installed snapshot, or null when there is none. */
	public Snapshot current() {
		File current = new File(root, CURRENT);
		byte[] bytes = current.isFile()
				? readOrNull(current)
				: null;
		String[] lines = bytes != null
				? new String(bytes, StandardCharsets.UTF_8).split("\n", -1)
				: new String[0];
		if (lines.length < 4) {
			return null;
		}
		File folder = new File(root, lines[0]);
		ProjectParseResult<ProjectPath> entry = ProjectPath.parse(lines[2]);
		ProjectParseResult<ProjectPath> origin = lines[3].isEmpty()
				? null
				: ProjectPath.parse(lines[3]);
		float quality;
		try {
			quality = Float.parseFloat(lines[1]);
		} catch (NumberFormatException e) {
			return null;
		}
		return folder.isDirectory() && entry.isSuccess()
				? new Snapshot(folder, entry.value(), quality,
						origin != null && origin.isSuccess()
								? origin.value()
								: null)
				: null;
	}

	/**
	 * Install the shader of a build as what the wallpaper shows: the
	 * files it is made of, as that build read them. A build with
	 * errors is refused, so that the wallpaper never shows an older
	 * picture than the one that was asked for. The snapshot before
	 * stays installed until this one is whole, and its files stay
	 * until the install after this one. Return why it was not
	 * installed, or null when it is.
	 *
	 * @param origin the file of the library this is, or null
	 */
	public String install(Preparation preparation, ProjectPath entry,
			float quality, ProjectPath origin) {
		if (preparation == null || preparation.plan() == null) {
			return "the shader has errors; fix them first";
		}
		Snapshot before = current();
		int number = 1;
		String[] names = root.list();
		for (String name : names != null ? names : new String[0]) {
			try {
				number = Math.max(number, Integer.parseInt(name) + 1);
			} catch (NumberFormatException e) {
				// Not a snapshot.
			}
		}
		File folder = new File(root, String.valueOf(number));
		if (!folder.mkdirs()) {
			return "the snapshot cannot be made";
		}
		ProjectFiles files = new DirectoryProjectFiles(folder);
		Map<ProjectPath, ProjectFiles.Content> read = preparation.files();
		for (SourceAssembly.Dependency used : preparation.uses()) {
			ProjectFiles.Content content = read.get(used.path());
			if (content != null && files.write(used.path(), content.bytes(),
					null) instanceof ProjectFiles.Refused refused &&
					refused.refusal() != ProjectFiles.Refusal.CONFLICT) {
				delete(folder);
				return used.path() + " cannot be copied: " +
						refused.reason();
			}
		}
		// Published in one rename; whoever reads sees the old one or
		// the new one whole.
		if (!StateFile.replace(new File(root, CURRENT),
				(folder.getName() + "\n" + quality + "\n" + entry + "\n" +
						(origin != null ? origin : "") + "\n").getBytes(
						StandardCharsets.UTF_8))) {
			delete(folder);
			return "the snapshot cannot be published";
		}
		// The one that was installed until now stays for who is
		// reading it; those before it go.
		for (String name : names != null ? names : new String[0]) {
			if (name.matches("[0-9]+") && (before == null ||
					!before.folder().getName().equals(name))) {
				delete(new File(root, name));
			}
		}
		return null;
	}

	private static byte[] readOrNull(File file) {
		try {
			return StateFile.read(file);
		} catch (java.io.IOException e) {
			return null;
		}
	}

	// A copy the app made; never a file of the user.
	private static void delete(File file) {
		File[] children = file.listFiles();
		for (File child : children != null ? children : new File[0]) {
			delete(child);
		}
		file.delete();
	}
}
