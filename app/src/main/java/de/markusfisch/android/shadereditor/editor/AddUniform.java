package de.markusfisch.android.shadereditor.editor;

import java.util.Collections;
import java.util.List;
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

	/**
	 * What adding statement to text does: the line that declares the
	 * same uniform, from removeStart to removeEnd (empty when there is
	 * none), goes, then inserted is put at insertAt of the text left.
	 */
	public record Change(int removeStart, int removeEnd, int insertAt,
			String inserted) {
	}

	/**
	 * Add statement to document as one undo step; return the offset it
	 * starts at.
	 */
	public static int apply(Document document, String statement) {
		return apply(document, Collections.singletonList(statement))[0];
	}

	/**
	 * Add statements to document, in this order, as one undo step;
	 * return the offsets they start at in the text that results.
	 */
	public static int[] apply(Document document, List<String> statements) {
		int[] at = new int[statements.size()];
		document.beginBatch();
		try {
			for (int i = 0; i < at.length; ++i) {
				String statement = statements.get(i);
				Change change = change(document.text().toString(),
						statement);
				if (change.removeEnd() > change.removeStart()) {
					document.replace(change.removeStart(),
							change.removeEnd(), Text.EMPTY);
				}
				document.replace(change.insertAt(), change.insertAt(),
						Text.of(change.inserted()));
				for (int j = 0; j < i; ++j) {
					at[j] = moved(at[j], change);
				}
				at[i] = change.insertAt() +
						change.inserted().indexOf(statement);
			}
		} finally {
			document.endBatch();
		}
		return at;
	}

	// Where offset, the start of a line, is after change.
	private static int moved(int offset, Change change) {
		int removed = change.removeEnd() - change.removeStart();
		if (offset >= change.removeEnd()) {
			offset -= removed;
		} else if (offset > change.removeStart()) {
			offset = change.removeStart();
		}
		return offset >= change.insertAt()
				? offset + change.inserted().length()
				: offset;
	}

	/** What adding statement to text does; see the class. */
	public static Change change(String text, String statement) {
		// A uniform in a comment is not the one declared.
		Matcher m = sameUniform(statement).matcher(
				CodeText.blank(text, false));
		int removeStart = 0;
		int removeEnd = 0;
		if (m.find()) {
			removeStart = m.start();
			removeEnd = m.end();
			text = text.substring(0, removeStart) + text.substring(removeEnd);
		}
		Place place = place(text);
		String inserted = place.afterLine()
				? "\n" + statement
				: statement + "\n";
		if (place.afterEndif()) {
			inserted = "\n" + inserted;
		}
		return new Change(removeStart, removeEnd, place.offset(), inserted);
	}

	/**
	 * Where a statement goes in text: at offset, which is the end of a
	 * line (before its line break) when afterLine, else the start of a
	 * line; afterEndif when the line before is an #endif, which keeps
	 * an empty line from it.
	 */
	public record Place(int offset, boolean afterLine, boolean afterEndif) {
	}

	/**
	 * Where a statement goes in text; see the class. Comments count as
	 * nothing, so a uniform in a comment is no place and a licence
	 * comment above #version keeps the head below it; "# if" is a
	 * conditional as "#if" is; a uniform ends at its semicolon, also
	 * one written over several lines.
	 */
	public static Place place(String text) {
		String code = CodeText.blank(text, false);
		int depth = 0;
		int lastUniform = -1;
		int head = -1;
		boolean headEndif = false;
		boolean inCode = false;
		int start = 0;
		while (start <= code.length()) {
			int end = code.indexOf('\n', start);
			if (end < 0) {
				end = code.length();
			}
			String line = code.substring(start, end).trim();
			String directive = directive(line);
			if (directive != null && directive.startsWith("if")) {
				++depth;
			} else if ("endif".equals(directive)) {
				depth = Math.max(0, depth - 1);
				if (depth == 0 && !inCode) {
					head = end;
					headEndif = true;
				}
			} else if (depth == 0 && UNIFORM.matcher(line).matches()) {
				// Its line is the one with its semicolon.
				int semicolon = code.indexOf(';', start);
				if (semicolon > -1) {
					int lineEnd = code.indexOf('\n', semicolon);
					end = lineEnd < 0 ? code.length() : lineEnd;
				}
				lastUniform = end;
				inCode = true;
			} else if (!inCode && ("version".equals(directive) ||
					"extension".equals(directive) ||
					(depth == 0 && PRECISION.matcher(line).matches()))) {
				head = end;
				headEndif = false;
			} else if (depth == 0 && !line.isEmpty() && directive == null) {
				inCode = true;
			}
			if (end == code.length()) {
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

	// The word of a preprocessor line, "if" for "# if FLAG", or null
	// for a line that is no directive.
	private static String directive(String line) {
		if (!line.startsWith("#")) {
			return null;
		}
		String rest = line.substring(1).trim();
		int end = 0;
		while (end < rest.length() &&
				Character.isLetter(rest.charAt(end))) {
			++end;
		}
		return rest.substring(0, end);
	}

	/**
	 * The lines that start with statement up to its semicolon: its
	 * words as they are, whatever characters a file name has, any run
	 * of blanks between them, and the name a whole word, so that
	 * "uniform float time;" never takes "uniform float timeScale;".
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
		return Pattern.compile(regex.append(
						"(?![A-Za-z0-9_]).*(?:\\n|$))").toString(),
				Pattern.MULTILINE);
	}
}
