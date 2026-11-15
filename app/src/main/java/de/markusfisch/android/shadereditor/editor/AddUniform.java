package de.markusfisch.android.shadereditor.editor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts a uniform statement into a document as Add uniform does: in
 * place of a line that declares the same uniform, else after the last
 * uniform line outside any #if block, else after the head of the file
 * (#version, #extension, precision lines and the #if blocks among
 * them), with an empty line after an #endif, else at the top. So a
 * statement never lands above #version or inside a conditional block,
 * where a host directive is not allowed (SPEC 3.1).
 */
public final class AddUniform {
	private static final Pattern UNIFORM = Pattern.compile(
			"^\\s*uniform\\b.*");
	private static final Pattern PRECISION = Pattern.compile(
			"^\\s*precision\\b.*");

	private AddUniform() {
	}

	/** Add statement to document as one undo step. */
	public static void apply(Document document, String statement) {
		document.beginBatch();
		try {
			String text = document.text().toString();
			Matcher m = sameUniform(statement).matcher(text);
			if (m.find()) {
				document.replace(m.start(), m.end(), Text.EMPTY);
				text = document.text().toString();
			}
			Place place = place(text);
			String inserted = place.afterLine()
					? "\n" + statement
					: statement + "\n";
			if (place.afterEndif()) {
				inserted = "\n" + inserted;
			}
			document.replace(place.offset(), place.offset(),
					Text.of(inserted));
		} finally {
			document.endBatch();
		}
	}

	/**
	 * Where a statement goes in text: at offset, which is the end of a
	 * line (before its line break) when afterLine, else the start of a
	 * line; afterEndif when the line before is an #endif, which keeps
	 * an empty line from it.
	 */
	public record Place(int offset, boolean afterLine, boolean afterEndif) {
	}

	/** Where a statement goes in text; see the class. */
	public static Place place(String text) {
		int depth = 0;
		int lastUniform = -1;
		int head = -1;
		boolean headEndif = false;
		boolean code = false;
		int start = 0;
		while (start <= text.length()) {
			int end = text.indexOf('\n', start);
			if (end < 0) {
				end = text.length();
			}
			String line = text.substring(start, end).trim();
			if (line.startsWith("#if")) {
				++depth;
			} else if (line.startsWith("#endif")) {
				depth = Math.max(0, depth - 1);
				if (depth == 0 && !code) {
					head = end;
					headEndif = true;
				}
			} else if (depth == 0 && UNIFORM.matcher(line).matches()) {
				lastUniform = end;
				code = true;
			} else if (!code && (line.startsWith("#version") ||
					line.startsWith("#extension") ||
					(depth == 0 && PRECISION.matcher(line).matches()))) {
				head = end;
				headEndif = false;
			} else if (depth == 0 && !line.isEmpty() &&
					!line.startsWith("//") && !line.startsWith("#")) {
				code = true;
			}
			if (end == text.length()) {
				break;
			}
			start = end + 1;
		}
		if (lastUniform > -1) {
			return new Place(lastUniform, true, false);
		} else if (head > -1) {
			return head < text.length()
					? new Place(head + 1, false, headEndif)
					: new Place(head, true, headEndif);
		}
		return new Place(0, false, false);
	}

	/**
	 * The lines that start with statement up to its semicolon: its
	 * words as they are, whatever characters a file name has, any run
	 * of blanks between them.
	 */
	public static Pattern sameUniform(String statement) {
		int p = statement.indexOf(';');
		String head = p > -1 ? statement.substring(0, p) : statement;
		StringBuilder regex = new StringBuilder("^(");
		String[] words = head.split(" ", -1);
		for (int i = 0; i < words.length; ++i) {
			if (i > 0) {
				regex.append("[ \\t]+");
			}
			regex.append(Pattern.quote(words[i]));
		}
		return Pattern.compile(regex.append(".*(?:\\n|$))").toString(),
				Pattern.MULTILINE);
	}
}
