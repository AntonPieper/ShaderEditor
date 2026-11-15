package de.markusfisch.android.shadereditor.navigation;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How a folder's README.md orders its listing, any folder at any
 * depth: each line starting with "## " begins a section named by the
 * rest of the line, and each list item that begins with a link to an
 * entry directly in the folder puts that entry into the section, in
 * that order, with the text after the link as its row's line.
 * Everything else is ignored: links to the web, to deeper paths or to
 * files that are not there, images, text, other headings. Nothing in
 * a README is ever an error, and it never changes what a tap does. An
 * entry linked twice counts once, where it is first linked. A fenced
 * code block is text, and a heading's closing hashes are not its name.
 */
public final class Readme {
	/** An entry the README links, and the line it gives it, or null. */
	public record Linked(String name, String line) {
	}

	/** A section of the README: its heading and the entries under it. */
	public record Section(String heading, List<Linked> entries) {
	}

	/** How much of a README is read, in bytes. */
	public static final int HEAD = 64 * 1024;
	/** The name a README has, in any case. */
	public static final String NAME = "readme.md";

	// A list item that starts with a link: "- ", "* ", "+ " or "1. ".
	private static final Pattern ITEM = Pattern.compile(
			"^\\s*(?:[-*+]|\\d+[.)])\\s+\\[([^\\]]*)]\\(\\s*(<[^>]*>|[^)\\s]*)" +
					"(?:\\s+\"[^\"]*\")?\\s*\\)(.*)$");

	// Closing hashes of a heading, after a blank.
	private static final Pattern CLOSING = Pattern.compile("\\s+#+\\s*$");

	private Readme() {
	}

	/** Return whether name is a README's. */
	public static boolean is(String name) {
		return name != null && name.toLowerCase(Locale.ROOT).equals(NAME);
	}

	/**
	 * The sections of text, a folder's README, for the entries of the
	 * folder named names; only sections that link an entry. A README
	 * that links nothing gives none.
	 */
	public static List<Section> sections(String text, Set<String> names) {
		Map<String, List<Linked>> sections = new LinkedHashMap<>();
		Set<String> linked = new HashSet<>();
		String heading = "";
		String fence = null;
		for (String line : text.split("\r?\n")) {
			// A fenced code block is text, whatever it shows.
			String trimmed = line.trim();
			if (fence != null) {
				if (trimmed.startsWith(fence)) {
					fence = null;
				}
				continue;
			} else if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
				fence = trimmed.substring(0, 3);
				continue;
			}
			if (line.startsWith("## ")) {
				// "## Start here ##": closing hashes are no part of it.
				heading = CLOSING.matcher(line.substring(3)).replaceFirst("")
						.trim();
				continue;
			}
			Matcher item = ITEM.matcher(line);
			if (!item.matches()) {
				continue;
			}
			String name = entry(item.group(2), names);
			if (name == null || !linked.add(name)) {
				continue;
			}
			List<Linked> entries = sections.get(heading);
			if (entries == null) {
				entries = new ArrayList<>();
				sections.put(heading, entries);
			}
			entries.add(new Linked(name, rest(item.group(3))));
		}
		List<Section> list = new ArrayList<>();
		for (Map.Entry<String, List<Linked>> section : sections.entrySet()) {
			list.add(new Section(section.getKey().isEmpty()
					? null
					: section.getKey(), section.getValue()));
		}
		return list;
	}

	// The name of the entry target links to when it is directly in the
	// folder, else null.
	private static String entry(String target, Set<String> names) {
		if (target.startsWith("<") && target.endsWith(">")) {
			target = target.substring(1, target.length() - 1);
		} else {
			try {
				target = URLDecoder.decode(target.replace("+", "%2B"),
						"UTF-8");
			} catch (UnsupportedEncodingException |
					IllegalArgumentException e) {
				return null;
			}
		}
		if (target.startsWith("./")) {
			target = target.substring(2);
		}
		if (target.endsWith("/")) {
			target = target.substring(0, target.length() - 1);
		}
		return !target.isEmpty() && !target.contains("/") &&
				!target.contains(":") && !target.contains("#") &&
				names.contains(target)
				? target
				: null;
	}

	// The text after the link, without a leading dash or colon.
	private static String rest(String after) {
		String line = after.trim();
		while (!line.isEmpty() && "-–—:".indexOf(line.charAt(0)) > -1) {
			line = line.substring(1).trim();
		}
		return line.isEmpty() ? null : line;
	}
}
