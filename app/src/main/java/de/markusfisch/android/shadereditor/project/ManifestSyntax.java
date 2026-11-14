package de.markusfisch.android.shadereditor.project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.Indentation;
import de.markusfisch.android.shadereditor.syntax.LexedLine;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * The manifest of a folder as the editor treats it: JSON, completing in
 * a string the keys the format knows where a key stands, and the names
 * of files as the value of "entry". The names come once they are
 * listed, on the main thread, which is the only one that completes.
 */
final class ManifestSyntax implements Syntax {
	private static final Syntax JSON = ShaderLanguage.JSON.syntax();
	private static final List<String> KEYS = List.of(
			ProjectManifest.VERSION_KEY, ProjectManifest.ENTRY_KEY);
	private static final String ENTRY = "\"" + ProjectManifest.ENTRY_KEY +
			"\"";

	private List<String> files = Collections.emptyList();

	/** Complete these names as the value of "entry". */
	void setFiles(List<String> files) {
		this.files = files;
	}

	@Override
	public int lexLine(CharSequence line, boolean breakFollows, int state,
			LineTokens out) {
		return JSON.lexLine(line, breakFollows, state, out);
	}

	@Override
	public Outline.Builder outline(int maxDeclarations, int maxNameChars) {
		return null;
	}

	@Override
	public void complete(LexedLine line, int column, int lineStart,
			Outline declared, boolean current, List<String> extraWords,
			Offer offer) {
		List<String> words = new ArrayList<>();
		int index = column > 0 ? line.indexAt(column - 1) : -1;
		String text = line.text();
		int start = index < 0 ? 0 : line.start(index);
		int end = index < 0 ? 0 : line.end(index);
		// In a string, not after its closing quote.
		if (index > -1 && text.charAt(start) == '"' &&
				(column < end || end - start < 2 ||
						text.charAt(end - 1) != '"' ||
						line.style(index) == Style.INVALID)) {
			String prefix = text.substring(start + 1, column);
			List<String> candidates = isEntry(line, index)
					? files
					: isKey(line, index) ? KEYS : Collections.emptyList();
			for (String word : candidates) {
				if (word.startsWith(prefix) && !word.equals(prefix)) {
					words.add(word);
				}
			}
			offer.offer(words, prefix.length());
			return;
		}
		offer.offer(words, 0);
	}

	@Override
	public Indentation indentation() {
		return JSON.indentation();
	}

	// Whether the string at index follows "entry" and a colon.
	private static boolean isEntry(LexedLine line, int index) {
		return index > 1 && is(line, index - 1, ":") &&
				is(line, index - 2, ENTRY);
	}

	// Whether the string at index is where a key of the object stands.
	private static boolean isKey(LexedLine line, int index) {
		return index == 0 || is(line, index - 1, "{") ||
				is(line, index - 1, ",");
	}

	private static boolean is(LexedLine line, int index, String token) {
		int start = line.start(index);
		return line.end(index) - start == token.length() &&
				line.text().startsWith(token, start);
	}
}
