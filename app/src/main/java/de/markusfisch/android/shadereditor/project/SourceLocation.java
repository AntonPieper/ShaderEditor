package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The origin of one prepared line: a 1-based line of a project file,
 * reached through {@code includedFrom}, or {@link #GENERATED}.
 */
public record SourceLocation(
		ProjectPath file,
		int line,
		SourceLocation includedFrom) {
	/** Lines the engine adds that no user file contains. */
	public static final SourceLocation GENERATED =
			new SourceLocation(null, 0, null);

	public boolean isGenerated() {
		return file == null;
	}

	/**
	 * The {@code #include} lines that led here, outermost first.
	 */
	public List<SourceLocation> includeChain() {
		List<SourceLocation> chain = new ArrayList<>();
		for (SourceLocation site = includedFrom; site != null;
				site = site.includedFrom) {
			chain.add(site);
		}
		Collections.reverse(chain);
		return chain;
	}

	@Override
	public String toString() {
		return isGenerated() ? "<generated>" : file + ":" + line;
	}
}
