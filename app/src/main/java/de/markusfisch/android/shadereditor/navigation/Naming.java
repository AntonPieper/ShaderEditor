package de.markusfisch.android.shadereditor.navigation;

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
