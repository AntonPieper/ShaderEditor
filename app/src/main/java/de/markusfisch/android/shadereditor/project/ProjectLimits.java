package de.markusfisch.android.shadereditor.project;

/**
 * Resource bounds of one build. Exceeding one is an error that names
 * the limit; input is never truncated silently.
 *
 * @param includeDepth how deeply includes may nest below the entry
 * @param sourceBytes  UTF-8 bytes of one assembled source
 * @param filesRead    distinct files read for one build
 * @param passes       pass files of one build
 */
public record ProjectLimits(
		int includeDepth,
		int sourceBytes,
		int filesRead,
		int passes) {
	public static final ProjectLimits DEFAULT = new ProjectLimits(
			16, 1024 * 1024, 64, 16);
}
