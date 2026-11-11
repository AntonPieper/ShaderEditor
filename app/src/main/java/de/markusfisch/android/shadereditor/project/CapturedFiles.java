package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads every file of one build once and keeps a copy of its bytes, so
 * all consumers of a file see the same bytes, also when the provider
 * changes its array later, and records each file read with its revision.
 * It is where the build's file limit is checked: a file beyond it is
 * not read and fails with that reason.
 */
final class CapturedFiles implements ProjectFiles {
	private final Map<ProjectPath, Read> captured = new HashMap<>();
	private final Map<ProjectPath, Content> contents = new LinkedHashMap<>();
	private final List<SourceAssembly.Dependency> dependencies =
			new ArrayList<>();
	private final ProjectFiles files;
	private final int limit;
	private int reads;

	CapturedFiles(ProjectFiles files, ProjectLimits limits) {
		this.files = files;
		this.limit = limits.filesRead();
	}

	/**
	 * Return why a read found no content, for the user, or null when it
	 * found content.
	 */
	static String problem(ProjectPath path, Read read) {
		if (read instanceof Content) {
			return null;
		} else if (read == null) {
			return "File not found: " + path;
		}
		return "Could not read " + path + (read instanceof Failed failed
				? ": " + failed.reason()
				: "");
	}

	@Override
	public Read read(ProjectPath path) {
		if (captured.containsKey(path)) {
			return captured.get(path);
		}
		Read read;
		if (reads < limit) {
			++reads;
			read = files.read(path);
		} else {
			read = new Failed("the build reads more than the limit of " +
					limit + " files");
		}
		String revision = null;
		if (read instanceof Content content) {
			Content copy = new Content(content.bytes().clone(),
					content.revision());
			contents.put(path, copy);
			revision = copy.revision();
			read = copy;
		}
		captured.put(path, read);
		dependencies.add(new SourceAssembly.Dependency(path, revision));
		return read;
	}

	/**
	 * Every file read, in order; a null revision means it was absent or
	 * could not be read.
	 */
	List<SourceAssembly.Dependency> dependencies() {
		return List.copyOf(dependencies);
	}

	/** The content of every file read, in order, as it was read. */
	Map<ProjectPath, Content> contents() {
		return contents;
	}
}
