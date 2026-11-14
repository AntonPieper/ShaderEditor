package de.markusfisch.android.shadereditor.syntax;

/**
 * What decides the indentation of a new line: the line comment that a
 * line starting with its first character continues, or null; the
 * characters that add a level when the line ends in one; and the
 * characters that open and close a level wherever they are.
 */
public record Indentation(String lineComment, String levelAfter,
		String opening, String closing) {
	/** C and the languages written like it. */
	public static final Indentation C = new Indentation("//", "{+-*/%^=",
			"(", ")");
}
