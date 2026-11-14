package de.markusfisch.android.shadereditor.project;

import java.util.Locale;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.JsonSyntax;
import de.markusfisch.android.shadereditor.syntax.PlainSyntax;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * What a text file is written in, from its name: the one place where
 * the ending of a file name has meaning. A text file whose name the
 * table does not list is GLSL too; another language is another row.
 * The names other shading languages are known by are listed as not
 * run, so that a file so named never means GLSL: a version that
 * runs such a language can then add it without changing what a
 * project of today means. A language that is not a shader at all, such
 * as JSON, is a row that does not run either.
 * Whether a file is text at all is not asked here, see
 * {@link FileKind}, and neither is what it is used as.
 * <p>
 * Whatever reads the text of a shader for a project asks its language
 * here: the pass graph, preparation and the files of an open project
 * know no shading language themselves. The editor asks it for the
 * {@link #syntax()} it colours, completes and indents a file with.
 */
public enum ShaderLanguage {
	GLSL(GlslSyntax.INSTANCE, null),
	/** A shading language this version does not run, as plain text. */
	UNSUPPORTED(PlainSyntax.INSTANCE, null),
	/** JSON, which is no shader. */
	JSON(JsonSyntax.INSTANCE, "JSON"),
	/** Markdown, which is no shader, edited as plain text. */
	MARKDOWN(PlainSyntax.INSTANCE, "Markdown");

	private record Row(String ending, ShaderLanguage language) {
	}

	// Endings compare without regard to case.
	private static final Row[] TABLE = {
			new Row(".frag", GLSL),
			new Row(".glsl", GLSL),
			new Row(".fs", GLSL),
			new Row(".fsh", GLSL),
			new Row(".wgsl", UNSUPPORTED),
			new Row(".agsl", UNSUPPORTED),
			new Row(".sksl", UNSUPPORTED),
			new Row(".hlsl", UNSUPPORTED),
			new Row(".slang", UNSUPPORTED),
			new Row(".json", JSON),
			new Row(".md", MARKDOWN),
	};

	/** The ending a new shader that runs or is a pass is named with. */
	public static final String PASS = ".frag";
	/** The ending code that is meant to be included is named with. */
	public static final String INCLUDE = ".glsl";

	private final Syntax syntax;
	// The name of a language that is no shader, or null.
	private final String noun;

	ShaderLanguage(Syntax syntax, String noun) {
		this.syntax = syntax;
		this.noun = noun;
	}

	/** Return the language of a text file named name. */
	public static ShaderLanguage of(String name) {
		ShaderLanguage listed = listed(name);
		return listed != null ? listed : GLSL;
	}

	/**
	 * Return why file, named as this language, cannot be a shader or
	 * be included by one, or null when this version runs the language.
	 */
	String refusal(ProjectPath file) {
		if (this == GLSL) {
			return null;
		} else if (noun != null) {
			return file + " is named as " + noun + ", which is not a " +
					"shader; rename it if it is GLSL";
		}
		return file + " is named as a shader in another language than " +
				"GLSL, which ShaderEditor does not run; rename it if it " +
				"is GLSL";
	}

	/** Return whether this version runs a file in this language. */
	public boolean runs() {
		return this == GLSL;
	}

	/** Return how the editor treats a file in this language. */
	public Syntax syntax() {
		return syntax;
	}

	/**
	 * Read the pass file as this language is written: its includes
	 * expanded, and what it asks of the host found and taken out of
	 * the text the compiler gets. Only for a language that runs.
	 */
	PassSource pass(ProjectFiles files, ProjectPath file,
			ProjectLimits limits, Builtins builtins) {
		if (this != GLSL) {
			throw new IllegalStateException(refusal(file));
		}
		return Glsl.pass(files, file, limits, builtins);
	}

	/**
	 * Return whether text of its own defines what a shader in this
	 * language starts at, so that the file can run.
	 */
	boolean runnable(Text text) {
		return this == GLSL && Glsl.definesMain(text);
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
