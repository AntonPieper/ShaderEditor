package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

/**
 * Replays keyboard sessions in the format KeyboardSession writes: makes
 * every call and app change of a session to a new TextInput on its
 * start text, records what that writes, and fails at the first line
 * that differs. App changes that follow each other, up to and including
 * an app select, were one update of the document and are replayed as
 * one batch.
 */
final class KeyboardReplay {
	private record Quoted(String text) {
	}

	private final String source;
	private final List<String> expected = new ArrayList<>();
	private final List<Integer> lineNumbers = new ArrayList<>();
	private final StringBuilder recorded = new StringBuilder();
	private int compared;
	private Document document;
	private TextInput input;
	private boolean inBatch;

	private KeyboardReplay(String source) {
		this.source = source;
	}

	static void replay(String source, List<String> lines) {
		new KeyboardReplay(source).run(lines);
	}

	static List<Object> tokenize(String line) {
		List<Object> tokens = new ArrayList<>();
		int i = 0;
		while (i < line.length()) {
			if (line.charAt(i) == ' ') {
				++i;
			} else if (line.charAt(i) == '"') {
				StringBuilder sb = new StringBuilder();
				for (++i; line.charAt(i) != '"'; ++i) {
					char c = line.charAt(i);
					if (c == '\\') {
						c = line.charAt(++i);
						switch (c) {
							case 'n' -> c = '\n';
							case 't' -> c = '\t';
							case 'u' -> {
								c = (char) Integer.parseInt(
										line.substring(i + 1, i + 5), 16);
								i += 4;
							}
							default -> {
							}
						}
					}
					sb.append(c);
				}
				++i;
				tokens.add(new Quoted(sb.toString()));
			} else {
				int end = line.indexOf(' ', i);
				end = end < 0 ? line.length() : end;
				String word = line.substring(i, end);
				tokens.add("null".equals(word) ? null : word);
				i = end;
			}
		}
		return tokens;
	}

	private void run(List<String> lines) {
		for (int i = 0; i < lines.size(); ++i) {
			String line = lines.get(i);
			if (!line.isBlank() && !line.startsWith("#")) {
				expected.add(line);
				lineNumbers.add(i + 1);
			}
		}
		for (int i = 0; i < expected.size(); ++i) {
			List<Object> tokens = tokenize(expected.get(i));
			boolean app = "app".equals(tokens.get(0));
			if (inBatch && !app) {
				endBatch();
			}
			apply(i, tokens);
			if (!inBatch) {
				compare();
			}
		}
		if (inBatch) {
			endBatch();
		}
		compare();
		if (compared < expected.size()) {
			fail(at(compared) + "expected\n  " + expected.get(compared) +
					"\nbut the editor wrote nothing more");
		}
	}

	private void apply(int index, List<Object> t) {
		String name = (String) t.get(0);
		switch (name) {
			case "start" -> {
				document = new Document(Text.of(string(t, 3)));
				document.select(number(t, 1), number(t, 2));
				input = new TextInput(document, (a, b, c, d) -> {
				}, new KeyboardSession(recorded));
			}
			case "end" -> input.closeConnection();
			case "updateSelection", "updateExtractedText" -> {
			}
			case "app" -> {
				if (!inBatch) {
					document.beginBatch();
					inBatch = true;
				}
				if ("select".equals(t.get(1))) {
					document.select(number(t, 2), number(t, 3));
					endBatch();
				} else {
					document.replace(number(t, 2), number(t, 3),
							Text.of(string(t, 4)));
				}
			}
			case "commitText" -> input.commitText(string(t, 1), number(t, 2));
			case "setComposingText" ->
					input.setComposingText(string(t, 1), number(t, 2));
			case "setComposingRegion" ->
					input.setComposingRegion(number(t, 1), number(t, 2));
			case "finishComposingText" -> input.finishComposingText();
			case "deleteSurroundingText" ->
					input.deleteSurroundingText(number(t, 1), number(t, 2));
			case "deleteSurroundingTextInCodePoints" ->
					input.deleteSurroundingTextInCodePoints(number(t, 1),
							number(t, 2));
			case "setSelection" ->
					input.setSelection(number(t, 1), number(t, 2));
			case "getTextBeforeCursor" ->
					input.getTextBeforeCursor(number(t, 1));
			case "getTextAfterCursor" ->
					input.getTextAfterCursor(number(t, 1));
			case "getSelectedText" -> input.getSelectedText();
			case "getSurroundingText" ->
					input.getSurroundingText(number(t, 1), number(t, 2));
			case "getExtractedText" -> input.getExtractedText(number(t, 1),
					Boolean.parseBoolean((String) t.get(2)));
			case "beginBatchEdit" -> input.beginBatchEdit();
			case "endBatchEdit" -> input.endBatchEdit();
			default -> fail(at(index) + "unknown call " + name);
		}
	}

	private void endBatch() {
		inBatch = false;
		document.endBatch();
	}

	// Fails at the first line the editor wrote that the session has not.
	private void compare() {
		String[] lines = recorded.toString().split("\n");
		for (; compared < lines.length; ++compared) {
			if (compared >= expected.size()) {
				fail(source + ": the editor wrote more:\n  " +
						lines[compared]);
			}
			if (!tokenize(lines[compared]).equals(
					tokenize(expected.get(compared)))) {
				fail(at(compared) + "expected\n  " + expected.get(compared) +
						"\nbut the editor wrote\n  " + lines[compared]);
			}
		}
	}

	private String at(int index) {
		return source + ":" + lineNumbers.get(index) + ": ";
	}

	private static String string(List<Object> tokens, int index) {
		Object token = tokens.get(index);
		return token == null ? null : ((Quoted) token).text();
	}

	private static int number(List<Object> tokens, int index) {
		return Integer.parseInt((String) tokens.get(index));
	}
}
