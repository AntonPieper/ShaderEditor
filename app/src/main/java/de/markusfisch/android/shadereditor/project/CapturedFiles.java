package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads every file of one build once, so all consumers of a file see
 * the same bytes, and records each file read with its revision.
 */
final class CapturedFiles implements ProjectFiles {
	private final Map<ProjectPath, Content> captured = new HashMap<>();
	private final List<SourceAssembly.Dependency> dependencies =
			new ArrayList<>();
	private final ProjectFiles files;

	CapturedFiles(ProjectFiles files) {
		this.files = files;
	}

	@Override
	public Content read(ProjectPath path) {
		if (captured.containsKey(path)) {
			return captured.get(path);
		}
		Content content = files.read(path);
		captured.put(path, content);
		dependencies.add(new SourceAssembly.Dependency(path,
				content != null ? content.revision() : null));
		return content;
	}

	/**
	 * Every file read, in order; a null revision means it was absent.
	 */
	List<SourceAssembly.Dependency> dependencies() {
		return List.copyOf(dependencies);
	}
}
