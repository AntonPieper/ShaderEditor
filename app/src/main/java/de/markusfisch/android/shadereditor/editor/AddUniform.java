package de.markusfisch.android.shadereditor.editor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts a uniform statement into a document as Add uniform does: in
 * place of a line that declares the same uniform, then after the last
 * uniform line, else after the last #endif with an empty line between,
 * else at the top.
 */
public final class AddUniform {
	private AddUniform() {
	}

	/** Add statement to document as one undo step. */
	public static void apply(Document document, String statement) {
		document.beginBatch();
		try {
			String text = document.text().toString();
			Matcher m = Pattern.compile(sameUniform(statement),
					Pattern.MULTILINE).matcher(text);
			if (m.find()) {
				document.replace(m.start(), m.end(), Text.EMPTY);
				text = document.text().toString();
			}
			int start = Analysis.uniformLineEnd(text);
			if (start > -1) {
				// Before the line break of the last uniform line.
				statement = "\n" + statement;
			} else {
				statement += "\n";
				if ((start = Analysis.endifEnd(text)) > -1) {
					statement = "\n" + statement;
				}
				// Past the line break, or 0 without an #endif.
				++start;
			}
			document.replace(start, start, Text.of(statement));
		} finally {
			document.endBatch();
		}
	}

	// A line that starts with the statement up to its semicolon.
	private static String sameUniform(String statement) {
		String regex = "^(" + statement.replace(" ", "[ \\t]+");
		int p = regex.indexOf(";");
		if (p > -1) {
			regex = regex.substring(0, p);
		}
		return regex + ".*\\n)$";
	}
}
