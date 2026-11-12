package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Replays keyboard sessions in the format KeyboardSession writes: opens,
 * calls, closes and app actions are made again to new TextInputs and
 * documents, on a clock set to the time of each line, and what that
 * writes must be the session's lines, times aside; it fails at the first
 * line that differs. The app's undo, redo and select are made here, any
 * other action goes to the handler. A session ends at truncated.
 */
final class KeyboardReplay {
	/** Does what the app was asked to do, as the view would. */
	interface Handler {
		void perform(Document document, String name, List<String> args);
	}

	private record Quoted(String text) {
	}

	// A word written as it is.
	private record Word(String text) {
		@Override
		public String toString() {
			return text;
		}
	}

	// What TextInput and the document write in answer to the others.
	private static final Set<String> OUTPUTS = Set.of("updateSelection",
			"updateExtractedText", "changed", "selected");

	private final String source;
	private final Handler handler;
	private final List<String> expected = new ArrayList<>();
	private final List<Integer> lineNumbers = new ArrayList<>();
	private final List<String> written = new ArrayList<>();
	private final List<TextInput> inputs = new ArrayList<>();
	private final long[] now = {0};
	private int compared;
	private final KeyboardSession session = new KeyboardSession(
			() -> now[0], Integer.MAX_VALUE);
	private Document document;

	private KeyboardReplay(String source, Handler handler) {
		this.source = source;
		this.handler = handler;
	}

	static void replay(String source, List<String> lines) {
		replay(source, lines, (document, name, args) ->
				fail(source + ": no handler for " + name));
	}

	static void replay(String source, List<String> lines, Handler handler) {
		new KeyboardReplay(source, handler).run(lines);
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
			String name = (String) tokens.get(2);
			if ("truncated".equals(name)) {
				expected.subList(i, expected.size()).clear();
				break;
			}
			if (!OUTPUTS.contains(name)) {
				now[0] = Long.parseLong((String) tokens.get(0));
				apply(i, tokens);
				compare();
			}
		}
		compare();
		if (written.size() < expected.size()) {
			int next = written.size();
			fail(at(next) + "expected\n  " + expected.get(next) +
					"\nbut the editor wrote nothing more");
		}
	}

	private void apply(int index, List<Object> t) {
		String who = (String) t.get(1);
		String name = (String) t.get(2);
		if ("app".equals(who)) {
			perform(name, t.subList(3, t.size()));
			return;
		}
		int connection = Integer.parseInt(who);
		if ("open".equals(name)) {
			String text = string(t, 5);
			if (text != null) {
				document = new Document(Text.of(text), () -> now[0]);
				document.select(number(t, 3), number(t, 4));
			}
			inputs.add(new TextInput(document, (a, b, c, d) -> {
			}, session));
			assertEquals(at(index) + "connection", inputs.size(), connection);
			return;
		}
		TextInput input = inputs.get(connection - 1);
		switch (name) {
			case "close" -> input.closeConnection();
			case "commitText" -> input.commitText(string(t, 3), number(t, 4));
			case "setComposingText" ->
					input.setComposingText(string(t, 3), number(t, 4));
			case "setComposingRegion" ->
					input.setComposingRegion(number(t, 3), number(t, 4));
			case "finishComposingText" -> input.finishComposingText();
			case "deleteSurroundingText" ->
					input.deleteSurroundingText(number(t, 3), number(t, 4));
			case "deleteSurroundingTextInCodePoints" ->
					input.deleteSurroundingTextInCodePoints(number(t, 3),
							number(t, 4));
			case "setSelection" ->
					input.setSelection(number(t, 3), number(t, 4));
			case "getTextBeforeCursor" ->
					input.getTextBeforeCursor(number(t, 3));
			case "getTextAfterCursor" ->
					input.getTextAfterCursor(number(t, 3));
			case "getSelectedText" -> input.getSelectedText();
			case "getSurroundingText" ->
					input.getSurroundingText(number(t, 3), number(t, 4));
			case "getExtractedText" -> input.getExtractedText(number(t, 3),
					Boolean.parseBoolean((String) t.get(4)));
			case "beginBatchEdit" -> input.beginBatchEdit();
			case "endBatchEdit" -> input.endBatchEdit();
			default -> fail(at(index) + "unknown call " + name);
		}
	}

	// Writes an action of the app as the app would, and makes it.
	private void perform(String name, List<Object> tokens) {
		Object[] values = new Object[tokens.size()];
		List<String> args = new ArrayList<>();
		for (int i = 0; i < values.length; ++i) {
			Object token = tokens.get(i);
			if (token instanceof Quoted quoted) {
				values[i] = quoted.text();
				args.add(quoted.text());
			} else {
				values[i] = token == null ? null : new Word((String) token);
				args.add((String) token);
			}
		}
		session.action(name, values);
		switch (name) {
			case "undo" -> document.undo();
			case "redo" -> document.redo();
			case "select" -> document.select(Integer.parseInt(args.get(0)),
					Integer.parseInt(args.get(1)));
			default -> handler.perform(document, name, args);
		}
	}

	// Fails at the first line the editor wrote that the session has not.
	private void compare() {
		String drained = session.drain();
		if (!drained.isEmpty()) {
			written.addAll(List.of(drained.split("\n")));
		}
		for (; compared < written.size(); ++compared) {
			String line = written.get(compared);
			if (compared >= expected.size()) {
				fail(source + ": the editor wrote more:\n  " + line);
			}
			List<Object> wrote = tokenize(line);
			List<Object> session = tokenize(expected.get(compared));
			if (!wrote.subList(1, wrote.size()).equals(
					session.subList(1, session.size()))) {
				fail(at(compared) + "expected\n  " + expected.get(compared) +
						"\nbut the editor wrote\n  " + line);
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
