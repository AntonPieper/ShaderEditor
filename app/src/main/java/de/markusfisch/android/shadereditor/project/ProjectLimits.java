package de.markusfisch.android.shadereditor.project;

/**
 * Resource bounds of one build. Each is checked before the read,
 * expansion or allocation it guards; exceeding one is an error that
 * names the limit, and input is never truncated silently. Zero is a
 * valid limit that admits nothing.
 *
 * @param includeDepth      how deeply includes may nest below the entry
 * @param sourceBytes       UTF-8 bytes of one assembled source
 * @param includeExpansions {@code #include} lines expanded for one
 *                          assembled source, also when they add no text
 * @param filesRead         distinct files read for one build
 * @param passes            pass files of one build
 * @param imagePixels       decoded pixels of one image
 */
public record ProjectLimits(
		int includeDepth,
		int sourceBytes,
		int includeExpansions,
		int filesRead,
		int passes,
		int imagePixels) {
	public static final ProjectLimits DEFAULT = new ProjectLimits(
			16, 1024 * 1024, 20000, 64, 16, 4096 * 4096);
}
