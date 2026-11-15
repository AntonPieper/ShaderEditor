package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.markusfisch.android.shadereditor.project.HostDirective;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.ShaderLanguage;
import de.markusfisch.android.shadereditor.project.SourceLocation;

/**
 * The two lines that make an image a texture of a shader (UX 4): the
 * directive that reads it, with its path from the shader's folder,
 * and a sampler of a name the shader does not use yet, from the
 * image's name. They are added only to a shader that runs and can be
 * changed, and that does not read the image yet.
 */
public final class InsertTexture {
	/** The lines to add, and the name of the sampler they declare. */
	public record Lines(String statement, String name) {
	}

	/**
	 * The file in the editor, as Insert as texture asks: its path from
	 * the root, its text, its language, whether its text defines what
	 * it starts at as its project knows (null while that is not
	 * known), whether the running shader includes it, and whether its
	 * folder cannot be changed.
	 */
	public record Target(ProjectPath path, String text,
			ShaderLanguage language, Boolean runs, boolean included,
			boolean readOnly) {
	}

	private static final Pattern PRAGMA = Pattern.compile(
			"^[ \\t]*#[ \\t]*pragma(.*)$", Pattern.MULTILINE);

	// Words a sampler cannot be named, besides what the text uses: the
	// keywords and reserved words of GLSL ES 1.00 and 3.00, and the
	// functions that read a texture.
	private static final Set<String> RESERVED = Set.of(
			"attribute", "const", "uniform", "varying", "break", "continue",
			"do", "for", "while", "if", "else", "in", "out", "inout", "float",
			"int", "void", "bool", "true", "false", "lowp", "mediump", "highp",
			"precision", "invariant", "discard", "return", "mat2", "mat3",
			"mat4", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4", "bvec2",
			"bvec3", "bvec4", "sampler2D", "samplerCube", "struct", "asm",
			"class", "union", "enum", "typedef", "template", "this", "packed",
			"goto", "switch", "default", "inline", "noinline", "volatile",
			"public", "static", "extern", "external", "interface", "flat",
			"long", "short", "double", "half", "fixed", "unsigned", "superp",
			"input", "output", "hvec2", "hvec3", "hvec4", "dvec2", "dvec3",
			"dvec4", "fvec2", "fvec3", "fvec4", "sampler1D", "sampler3D",
			"sampler1DShadow", "sampler2DShadow", "sampler2DRect",
			"sampler3DRect", "sampler2DRectShadow", "sizeof", "cast",
			"namespace", "using", "layout", "centroid", "smooth", "case",
			"uint", "uvec2", "uvec3", "uvec4", "mat2x2", "mat2x3", "mat2x4",
			"mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3", "mat4x4",
			"samplerCubeShadow", "sampler2DArray", "sampler2DArrayShadow",
			"isampler2D", "isampler3D", "isamplerCube", "isampler2DArray",
			"usampler2D", "usampler3D", "usamplerCube", "usampler2DArray",
			"coherent", "restrict", "readonly", "writeonly", "resource",
			"atomic_uint", "noperspective", "patch", "sample", "subroutine",
			"common", "partition", "active", "filter", "image1D", "image2D",
			"image3D", "imageCube", "iimage1D", "iimage2D", "iimage3D",
			"iimageCube", "uimage1D", "uimage2D", "uimage3D", "uimageCube",
			"image1DArray", "image2DArray", "iimage1DArray", "iimage2DArray",
			"uimage1DArray", "uimage2DArray", "imageBuffer", "iimageBuffer",
			"uimageBuffer", "sampler1DArray", "sampler1DArrayShadow",
			"isampler1D", "isampler1DArray", "usampler1D", "usampler1DArray",
			"isampler2DRect", "usampler2DRect", "samplerBuffer",
			"isamplerBuffer", "usamplerBuffer", "sampler2DMS", "isampler2DMS",
			"usampler2DMS", "sampler2DMSArray", "isampler2DMSArray",
			"usampler2DMSArray", "texture", "texture2D", "texture2DProj",
			"texture2DLod", "textureCube", "textureLod", "textureSize",
			"texelFetch", "main");

	/** Whether Insert as texture is offered, and else why not. */
	public enum Offer {
		/** The two lines can be added to the file in the editor. */
		OFFERED,
		/** Nothing of the image's folder is in the editor. */
		NO_SHADER_HERE,
		/**
		 * The file in the editor is of the folder but no shader: no
		 * GLSL, no file that runs, or one the running shader includes.
		 */
		NOT_A_SHADER,
		/** The shader in the editor cannot be changed. */
		READ_ONLY,
		/** The shader in the editor reads the image already. */
		READS_ALREADY,
		/**
		 * The editor shows another file than when the offer was made,
		 * as after an import that opened its shader.
		 */
		ANOTHER_FILE
	}

	private InsertTexture() {
	}

	/**
	 * Whether the two lines that read image, a path from the root, may
	 * be added to target, the file in the editor, null for none or a
	 * file of another folder: only to a GLSL file that runs, as a
	 * directive may stand only there (SPEC 2.1, 3.1), no JSON, no
	 * Markdown, never an include, also one that defines main, when the
	 * running shader includes it; only where it can be changed; and
	 * not where the file reads the image already. Where its project
	 * does not know yet whether it runs, its name decides.
	 */
	public static Offer offer(Target target, ProjectPath image) {
		if (target == null) {
			return Offer.NO_SHADER_HERE;
		} else if (target.language() != ShaderLanguage.GLSL ||
				target.included() || Boolean.FALSE.equals(target.runs()) ||
				(target.runs() == null && target.path().getFileName()
						.toLowerCase(Locale.ROOT)
						.endsWith(ShaderLanguage.INCLUDE))) {
			return Offer.NOT_A_SHADER;
		} else if (target.readOnly()) {
			return Offer.READ_ONLY;
		} else if (reads(target.text(), target.path(), image)) {
			return Offer.READS_ALREADY;
		}
		return Offer.OFFERED;
	}

	/**
	 * Whether the lines offered for opened may be added when Insert is
	 * pressed, to now, the file in the editor then: only to the same
	 * file, and only where the rule still offers them, for its text now.
	 */
	public static Offer atInsert(Target opened, Target now,
			ProjectPath image) {
		if (opened == null || now == null ||
				!opened.path().equals(now.path())) {
			return Offer.ANOTHER_FILE;
		}
		return offer(now, image);
	}

	/**
	 * Whether text, of the file at edited, reads image, both paths from
	 * the root, with a {@code #pragma se input} outside comments.
	 */
	public static boolean reads(String text, ProjectPath edited,
			ProjectPath image) {
		Matcher pragma = PRAGMA.matcher(CodeText.blank(text, false));
		while (pragma.find()) {
			String argument = pragma.group(1).trim();
			if (!HostDirective.isHostPragma(argument)) {
				continue;
			}
			HostDirective directive = HostDirective.parse(argument,
					new SourceLocation(edited, 1, null));
			if (directive.command() == HostDirective.Command.INPUT &&
					image.equals(directive.path())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The lines that make image, a path from the project's root, a
	 * texture of the file at edited, also from the root, whose text is
	 * text.
	 */
	public static Lines of(String text, String edited, String image) {
		String name = name(text, image);
		return new Lines("#pragma se input \"" + relative(edited, image) +
				"\"\nuniform sampler2D " + name + ";", name);
	}

	/** The path of to as the file at from refers to it. */
	public static String relative(String from, String to) {
		List<String> base = new ArrayList<>(List.of(from.split("/")));
		base.remove(base.size() - 1);
		List<String> target = new ArrayList<>(List.of(to.split("/")));
		int same = 0;
		while (same < base.size() && same < target.size() - 1 &&
				base.get(same).equals(target.get(same))) {
			++same;
		}
		List<String> parts = new ArrayList<>(Collections.nCopies(
				base.size() - same, ".."));
		parts.addAll(target.subList(same, target.size()));
		return String.join("/", parts);
	}

	/**
	 * A sampler name from the image's file name that text does not use
	 * as a word yet: its letters, digits and underscores, starting
	 * with a letter, then 2, 3 and on where it is taken.
	 */
	public static String name(String text, String image) {
		String file = image.substring(image.lastIndexOf('/') + 1);
		int dot = file.lastIndexOf('.');
		String stem = dot > 0 ? file.substring(0, dot) : file;
		StringBuilder name = new StringBuilder();
		boolean upper = false;
		for (int i = 0; i < stem.length(); ++i) {
			char c = stem.charAt(i);
			if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
					(c >= '0' && c <= '9')) {
				name.append(upper && name.length() > 0
						? Character.toUpperCase(c)
						: c);
				upper = false;
			} else {
				// paper-grain.png is paperGrain.
				upper = true;
			}
		}
		if (name.length() == 0 || Character.isDigit(name.charAt(0))) {
			name.insert(0, "image");
		}
		name.setCharAt(0, Character.toLowerCase(name.charAt(0)));
		String base = name.toString();
		String candidate = base;
		for (int n = 2; used(text, candidate); ++n) {
			candidate = base + n;
		}
		return candidate;
	}

	// A word in a comment or a string, such as the path of a directive,
	// is no use of a name.
	private static boolean used(String text, String name) {
		return RESERVED.contains(name) ||
				name.toLowerCase(Locale.ROOT).startsWith("gl_") ||
				Pattern.compile("\\b" + Pattern.quote(name) + "\\b")
						.matcher(CodeText.blank(text, true)).find();
	}
}
