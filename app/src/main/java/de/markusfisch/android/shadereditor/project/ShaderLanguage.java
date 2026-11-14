package de.markusfisch.android.shadereditor.project;

import java.util.Locale;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;

/**
 * What a text file is written in, from its name: the one place where
 * the ending of a file name has meaning. A text file whose name the
 * table does not list is GLSL too; another language is another row.
 * Whether a file is text at all is not asked here, see
 * {@link FileKind}, and neither is what it is used as.
 * <p>
 * Whatever reads the text of a shader for a project asks its language
 * here: the pass graph, preparation and the files of an open project
 * know no shading language themselves.
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
	 * Read the pass file as this language is written: its includes
	 * expanded, and what it asks of the host found and taken out of
	 * the text the compiler gets.
	 */
	PassSource pass(ProjectFiles files, ProjectPath file,
			ProjectLimits limits, Builtins builtins) {
		return Glsl.pass(files, file, limits, builtins);
	}

	/**
	 * Return whether text of its own defines what a shader in this
	 * language starts at, so that the file can run.
	 */
	boolean runnable(Text text) {
		return Glsl.definesMain(text);
	}

	/** Return what a file must define to run, as a message names it. */
	String entryPoint() {
		return Glsl.ENTRY_POINT;
	}

	/**
	 * Return how this language declares a texture that is read as
	 * kind under name, as a message quotes it.
	 */
	String declaration(TextureKind kind, String name) {
		return Glsl.declaration(kind, name);
	}

	/** Return the type of that declaration alone. */
	String type(TextureKind kind) {
		return HostDirectives.keyword(kind);
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
