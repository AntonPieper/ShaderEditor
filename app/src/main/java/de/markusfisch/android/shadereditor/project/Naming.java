package de.markusfisch.android.shadereditor.project;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a new name of a file or folder may be. Names are compared
 * ignoring case, because many folders a phone offers, like its shared
 * storage, cannot hold two names that differ only in case.
 */
public final class Naming {
	/** Why a name cannot be used, or nothing. */
	public enum Problem {
		NONE,
		EMPTY,
		/** It has a character no folder may hold in a name. */
		INVALID_CHARACTER,
		/** It starts with a dot, so the drawer would not show it. */
		HIDDEN,
		/** The folder has a file or folder of that name. */
		TAKEN
	}

	private static final String INVALID = "/\\\":*?<>|";
	// The longest stem made from a title.
	private static final int LONGEST = 100;
	// And the most bytes of it in UTF-8: the storage of a phone takes
	// names of 255 bytes, and an ending and a number come behind it.
	private static final int LONGEST_BYTES = 200;

	private final Set<String> taken = new HashSet<>();

	/** @param names the names in the folder now */
	public Naming(Iterable<String> names) {
		for (String name : names) {
			taken.add(fold(name));
		}
	}

	/** Why name cannot be given in the folder, or NONE. */
	public Problem check(String name) {
		if (name.trim().isEmpty()) {
			return Problem.EMPTY;
		}
		for (int i = 0, length = name.length(); i < length; ++i) {
			char c = name.charAt(i);
			if (INVALID.indexOf(c) > -1 || Character.isISOControl(c)) {
				return Problem.INVALID_CHARACTER;
			}
		}
		if (name.startsWith(".")) {
			return Problem.HIDDEN;
		} else if (taken.contains(fold(name))) {
			return Problem.TAKEN;
		}
		return Problem.NONE;
	}

	/**
	 * The stem of a file name made from title, which may be anything a
	 * shader was once called, or null: what {@link #check} would refuse
	 * as a character becomes '_', white space at both ends and dots at
	 * the start go, as does an ending shaders are named with, and what
	 * is longer than a name should be is cut. fallback when nothing is
	 * left. The stem with an ending is a name check takes, unless it
	 * is taken; {@link #unused} sees to that.
	 */
	public static String stem(String title, String fallback) {
		StringBuilder sb = new StringBuilder();
		String text = title != null
				? Normalizer.normalize(title, Normalizer.Form.NFC).trim()
				: "";
		for (int i = 0, length = text.length(); i < length; ++i) {
			char c = text.charAt(i);
			sb.append(INVALID.indexOf(c) > -1 || Character.isISOControl(c)
					? '_'
					: c);
		}
		String stem = sb.toString().trim();
		int start = 0;
		while (start < stem.length() && stem.charAt(start) == '.') {
			++start;
		}
		stem = stem.substring(start).trim();
		int dot = stem.lastIndexOf('.');
		if (dot > 0 && ShaderLanguage.listed(stem) == ShaderLanguage.GLSL) {
			stem = stem.substring(0, dot).trim();
		}
		if (stem.length() > LONGEST) {
			int end = Character.isHighSurrogate(stem.charAt(LONGEST - 1))
					? LONGEST - 1
					: LONGEST;
			stem = stem.substring(0, end).trim();
		}
		while (stem.getBytes(StandardCharsets.UTF_8).length >
				LONGEST_BYTES) {
			stem = stem.substring(0, stem.offsetByCodePoints(
					stem.length(), -1)).trim();
		}
		return stem.isEmpty() ? fallback : stem;
	}

	/** Take name as one that is in the folder now. */
	public void take(String name) {
		taken.add(fold(name));
	}

	/**
	 * The first of "stem.ext", "stem 2.ext", "stem 3.ext" and so on
	 * that is not taken; extension starts with its dot, or is empty.
	 */
	public String unused(String stem, String extension) {
		String name = stem + extension;
		for (int n = 2; taken.contains(fold(name)); ++n) {
			name = stem + " " + n + extension;
		}
		return name;
	}

	/**
	 * A name for a copy of the file name: "name copy.ext", or the
	 * first of "name copy 2.ext" and on that is not taken.
	 */
	public String copyOf(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 1) {
			dot = name.length();
		}
		return unused(name.substring(0, dot) + " copy", name.substring(dot));
	}

	private static String fold(String name) {
		return name.toLowerCase(Locale.ROOT);
	}
}
