package de.markusfisch.android.shadereditor.project;

import java.util.Locale;

/**
 * What a text file is written in, from its name: the one place where
 * the ending of a file name has meaning. A text file whose name the
 * table does not list is GLSL too, so the table adds nothing today but
 * the names the app knows as shaders; another language is another row.
 * Whether a file is text at all is not asked here, see
 * {@link FileKind}, and neither is what it is used as.
 */
public enum ShaderLanguage {
	GLSL;

	private record Row(String ending, ShaderLanguage language) {
	}

	// Endings compare without regard to case.
	private static final Row[] TABLE = {
			new Row(".frag", GLSL),
			new Row(".glsl", GLSL),
			new Row(".fs", GLSL),
			new Row(".fsh", GLSL),
	};

	/** The ending a new shader that runs or is a pass is named with. */
	public static final String PASS = ".frag";
	/** The ending code that is meant to be included is named with. */
	public static final String INCLUDE = ".glsl";

	/** Return the language of a text file named name. */
	public static ShaderLanguage of(String name) {
		ShaderLanguage listed = listed(name);
		return listed != null ? listed : GLSL;
	}

	/**
	 * Return the language the table lists for name, or null when it
	 * lists none: such a name is usually a shader's, which a hint for
	 * an icon or an offer to open a file may go by.
	 */
	public static ShaderLanguage listed(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		for (Row row : TABLE) {
			if (lower.endsWith(row.ending())) {
				return row.language();
			}
		}
		return null;
	}
}
