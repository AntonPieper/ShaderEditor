package de.markusfisch.android.shadereditor.project;

import java.util.List;

/**
 * What a file is to the running shader, as its last build found: the
 * file that runs, a pass, the first frame of a pass, an include, or a
 * texture, and which files it is that to, in the order they were
 * read: the pass whose first frame it is, the files that include it,
 * the passes that read it.
 */
public record FileRole(Kind kind, List<ProjectPath> of) {
	/** What a file is, the first that applies when it is two things. */
	public enum Kind {
		RUNS,
		FIRST_FRAME,
		PASS,
		INCLUDE,
		TEXTURE
	}

	public FileRole {
		of = List.copyOf(of);
	}
}
