package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The README of an imported shader: whose work it is, what each file
 * of the folder is, in the order the drawer lists them by, and what
 * the import did not carry over.
 */
final class ShaderToyReadme {
	private ShaderToyReadme() {
	}

	// What tells that a comment speaks of a licence.
	private static final Pattern LICENCE = Pattern.compile(
			"(?i)licen[cs]e|copyright|\\(c\\)|\u00a9|creative commons|" +
					"\\bCC[ -]?(0|BY)|public domain|all rights reserved|" +
					"\\bMIT\\b|\\bGPL|\\bapache\\b|SPDX");
	// The most lines of a licence that are repeated.
	private static final int LONGEST = 24;

	/**
	 * The comment code begins with, without its marks, when it speaks
	 * of a licence, which is where ShaderToy's authors state theirs;
	 * else null.
	 */
	static String licence(String code) {
		List<String> lines = new ArrayList<>();
		int at = 0;
		while (true) {
			while (at < code.length() &&
					Character.isWhitespace(code.charAt(at))) {
				++at;
			}
			int end;
			if (code.startsWith("//", at)) {
				end = code.indexOf('\n', at);
				end = end < 0 ? code.length() : end;
				lines.add(code.substring(at + 2, end));
			} else if (code.startsWith("/*", at)) {
				end = code.indexOf("*/", at + 2);
				end = end < 0 ? code.length() : end;
				for (String line : code.substring(at + 2, end).split("\n")) {
					lines.add(line.replaceFirst("^\\s*\\*", ""));
				}
				end = Math.min(code.length(), end + 2);
			} else {
				break;
			}
			at = end;
		}
		StringBuilder sb = new StringBuilder();
		for (String line : lines) {
			String text = line.replaceAll("^[/*\\s]+|[\\s*]+$", "");
			if (!text.isEmpty() && sb.length() < LONGEST * 200) {
				sb.append(sb.length() > 0 ? "\n" : "").append(text);
			}
		}
		String comment = sb.toString();
		if (!LICENCE.matcher(comment).find()) {
			return null;
		}
		String[] kept = comment.split("\n");
		return kept.length <= LONGEST
				? comment
				: String.join("\n", Arrays.copyOf(kept, LONGEST)) +
				"\n\u2026";
	}

	/**
	 * @param files   the files of the folder in the order to list them,
	 *                each with what it is
	 * @param buffers whether a file is a buffer
	 * @param licence the licence the code states, see {@link #licence},
	 *                or null
	 */
	static String text(ShaderToyShader shader, Map<String, String> files,
			boolean buffers, String licence, List<String> warnings) {
		String name = shader.name() != null &&
				!shader.name().trim().isEmpty()
				? ShaderToyHeader.line(shader.name())
				: "A shader from ShaderToy";
		StringBuilder sb = new StringBuilder("# " + name + "\n\n");
		String author = shader.author() != null
				? ShaderToyHeader.line(shader.author())
				: "";
		// Only an id as ShaderToy has them is made a link.
		String link = shader.id() != null &&
				shader.id().matches("[A-Za-z0-9]{1,16}")
				? "https://www.shadertoy.com/view/" + shader.id()
				: null;
		if (!author.isEmpty() || link != null) {
			sb.append(author.isEmpty() ? "From" : "By " + author + " on")
					.append(" ShaderToy")
					.append(link != null ? ": " + link : "")
					.append("\n\n");
		}
		sb.append("This shader is its author's work. ");
		if (licence != null) {
			sb.append("Its code begins with this licence:\n\n> ")
					.append(licence.replace("\n", "\n> "))
					.append("\n\n");
		} else {
			sb.append("The import found no licence at the top of its " +
					"code, so it is under Creative Commons Attribution-" +
					"NonCommercial-ShareAlike 3.0 Unported " +
					"(https://creativecommons.org/licenses/by-nc-sa/3.0/), " +
					"ShaderToy's default for shaders without a licence of " +
					"their own: name the author, no commercial use, share " +
					"changes under the same terms.\n\n");
		}
		sb.append("## Files\n");
		for (Map.Entry<String, String> file : files.entrySet()) {
			sb.append("- [").append(file.getKey()).append("](")
					.append(file.getKey().replace(" ", "%20")).append(") ")
					.append(file.getValue()).append('\n');
		}
		if (buffers) {
			sb.append("\nBuffers keep 16-bit floats here; ShaderToy " +
					"keeps 32-bit ones. A shader that packs data into " +
					"the bits of a float may differ.\n");
		}
		if (!warnings.isEmpty()) {
			sb.append("\n## Not carried over\n");
			for (String warning : warnings) {
				sb.append("- ").append(warning).append('\n');
			}
		}
		return sb.toString();
	}
}
