package de.markusfisch.android.shadereditor.project;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The optional {@code shader-project.json} in a project root.
 */
public record ProjectManifest(int projectVersion, ProjectPath entry) {
	public static final ProjectPath FILE = ProjectPath.parse(
			"shader-project.json").value();
	/** What a missing manifest means, permanently. */
	public static final ProjectManifest DEFAULT = new ProjectManifest(
			1, ProjectPath.parse("main.glsl").value());

	private static final String VERSION_KEY = "projectVersion";
	private static final String ENTRY_KEY = "entry";

	public static ProjectParseResult<ProjectManifest> parse(String text) {
		Reader reader = new Reader(text);
		Map<String, Value> fields;
		try {
			fields = reader.readObject();
		} catch (SyntaxException e) {
			return ProjectParseResult.error(e.getMessage(), FILE, e.line);
		}

		ProjectPath entry = DEFAULT.entry;
		for (Map.Entry<String, Value> field : fields.entrySet()) {
			Value value = field.getValue();
			switch (field.getKey()) {
				case VERSION_KEY:
					if (value.type != Type.NUMBER || !"1".equals(value.text)) {
						return ProjectParseResult.error(
								"Unsupported projectVersion " + value.display() +
										", this app reads version 1",
								FILE, value.line);
					}
					break;
				case ENTRY_KEY:
					if (value.type != Type.STRING) {
						return ProjectParseResult.error(
								"\"entry\" must be a string, found " +
										value.display(),
								FILE, value.line);
					}
					ProjectParseResult<ProjectPath> path =
							ProjectPath.parse(value.text);
					if (!path.isSuccess()) {
						return ProjectParseResult.error(
								"Invalid entry: " + path.diagnostic().message(),
								FILE, value.line);
					}
					if (path.value().getKind() != ProjectPath.Kind.SHADER) {
						return ProjectParseResult.error(
								"Entry must be a .glsl file: " + value.text,
								FILE, value.line);
					}
					entry = path.value();
					break;
				default:
					return ProjectParseResult.error(
							"Unknown key \"" + field.getKey() + "\"",
							FILE, value.line);
			}
		}
		return ProjectParseResult.success(new ProjectManifest(1, entry));
	}

	private enum Type {
		STRING,
		NUMBER,
		LITERAL
	}

	/** {@code text} is the decoded string, or the raw token otherwise. */
	private record Value(Type type, String text, int line) {
		private String display() {
			return type == Type.STRING ? "\"" + text + "\"" : text;
		}
	}

	private static final class SyntaxException extends Exception {
		private final int line;

		private SyntaxException(String message, int line) {
			super(message);
			this.line = line;
		}
	}

	/**
	 * Strict reader for a flat JSON object whose values are strings,
	 * numbers or literals. Anything else is rejected.
	 */
	private static final class Reader {
		private final String text;
		private int pos;

		private Reader(String text) {
			this.text = text;
			pos = !text.isEmpty() && text.charAt(0) == 0xFEFF ? 1 : 0;
		}

		private Map<String, Value> readObject() throws SyntaxException {
			Map<String, Value> fields = new LinkedHashMap<>();
			expect('{', "The manifest must be a JSON object");
			if (!consume('}')) {
				do {
					skipWhitespace();
					int keyLine = line();
					String key = readString();
					if (fields.containsKey(key)) {
						throw new SyntaxException(
								"Duplicate key \"" + key + "\"", keyLine);
					}
					expect(':', "Expected ':' after key");
					fields.put(key, readValue());
				} while (consume(','));
				expect('}', "Expected ',' or '}'");
			}
			skipWhitespace();
			if (pos < text.length()) {
				throw error("Unexpected text after the JSON object");
			}
			return fields;
		}

		private Value readValue() throws SyntaxException {
			skipWhitespace();
			int line = line();
			if (peek() == '"') {
				return new Value(Type.STRING, readString(), line);
			}
			int start = pos;
			while (pos < text.length() && "+-.0123456789Eabcdeflnrstu"
					.indexOf(text.charAt(pos)) > -1) {
				++pos;
			}
			String token = text.substring(start, pos);
			if (token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
				return new Value(Type.NUMBER, token, line);
			} else if (token.matches("true|false|null")) {
				return new Value(Type.LITERAL, token, line);
			}
			throw error("Values must be strings or numbers");
		}

		private String readString() throws SyntaxException {
			expect('"', "Expected a string in double quotes");
			StringBuilder sb = new StringBuilder();
			while (true) {
				if (pos >= text.length()) {
					throw error("Unterminated string");
				}
				char c = text.charAt(pos++);
				if (c == '"') {
					return sb.toString();
				} else if (c < 0x20) {
					--pos;
					throw error("Control character in string");
				} else if (c != '\\') {
					sb.append(c);
					continue;
				}
				char escape = pos < text.length() ? text.charAt(pos++) : 0;
				int simple = "\"\\/bfnrt".indexOf(escape);
				if (simple > -1) {
					sb.append("\"\\/\b\f\n\r\t".charAt(simple));
				} else if (escape == 'u' && pos + 4 <= text.length() &&
						text.substring(pos, pos + 4).matches("[0-9a-fA-F]{4}")) {
					sb.append((char) Integer.parseInt(
							text.substring(pos, pos + 4), 16));
					pos += 4;
				} else {
					throw error("Invalid escape sequence in string");
				}
			}
		}

		private void expect(char c, String message) throws SyntaxException {
			if (!consume(c)) {
				throw error(message);
			}
		}

		private boolean consume(char c) {
			skipWhitespace();
			if (peek() == c) {
				++pos;
				return true;
			}
			return false;
		}

		private char peek() {
			return pos < text.length() ? text.charAt(pos) : 0;
		}

		private void skipWhitespace() {
			while (pos < text.length() && " \t\n\r".indexOf(text.charAt(pos)) > -1) {
				++pos;
			}
		}

		private int line() {
			int line = 1;
			for (int i = 0; i < pos; ++i) {
				char c = text.charAt(i);
				if (c == '\n' || (c == '\r' && (i + 1 >= text.length() ||
						text.charAt(i + 1) != '\n'))) {
					++line;
				}
			}
			return line;
		}

		private SyntaxException error(String message) {
			return new SyntaxException(message, line());
		}
	}
}
