package de.markusfisch.android.shadereditor.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The two lines that make an image a texture of a shader (UX 4): the
 * directive that reads it, with its path from the shader's folder,
 * and a sampler of a name the shader does not use yet, from the
 * image's name.
 */
public final class InsertTexture {
	/** The lines to add, and the name of the sampler they declare. */
	public record Lines(String statement, String name) {
	}

	// Words a sampler cannot be named, besides what the text uses.
	private static final Set<String> RESERVED = Set.of("texture",
			"texture2D", "sampler2D", "uniform", "main", "in", "out",
			"input", "output", "sample", "filter", "precision", "float",
			"int", "bool", "vec2", "vec3", "vec4", "mat2", "mat3", "mat4",
			"if", "else", "for", "while", "do", "return", "true", "false",
			"const", "struct", "void", "discard", "break", "continue");

	private InsertTexture() {
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

	private static boolean used(String text, String name) {
		return RESERVED.contains(name) ||
				name.toLowerCase(Locale.ROOT).startsWith("gl_") ||
				Pattern.compile("\\b" + Pattern.quote(name) + "\\b")
						.matcher(text).find();
	}
}
