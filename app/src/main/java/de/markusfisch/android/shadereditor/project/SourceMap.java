package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The origin of every line of an assembled source, in order.
 */
public record SourceMap(List<SourceLocation> lines) {
	public SourceMap {
		lines = List.copyOf(lines);
	}

	/**
	 * Return the origin of a 1-based prepared line, or null when the
	 * line is outside the assembled source.
	 */
	public SourceLocation locate(int preparedLine) {
		return preparedLine < 1 || preparedLine > lines.size()
				? null
				: lines.get(preparedLine - 1);
	}

	/**
	 * Return a map with count generated lines after the 1-based prepared
	 * line afterLine, 0 for the top.
	 */
	public SourceMap insertGenerated(int afterLine, int count) {
		List<SourceLocation> updated = new ArrayList<>(lines);
		updated.addAll(afterLine, Collections.nCopies(count,
				SourceLocation.GENERATED));
		return new SourceMap(updated);
	}
}
