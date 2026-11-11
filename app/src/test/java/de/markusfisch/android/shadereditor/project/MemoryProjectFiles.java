package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MemoryProjectFiles implements ProjectFiles {
	private final Map<ProjectPath, Content> files = new HashMap<>();
	final List<ProjectPath> readPaths = new ArrayList<>();
	int reads;

	MemoryProjectFiles put(String path, String text) {
		return put(path, text.getBytes(StandardCharsets.UTF_8));
	}

	MemoryProjectFiles put(String path, byte[] bytes) {
		files.put(ProjectPath.parse(path).value(),
				new Content(bytes, "r" + files.size()));
		return this;
	}

	@Override
	public Content read(ProjectPath path) {
		++reads;
		readPaths.add(path);
		return files.get(path);
	}
}
