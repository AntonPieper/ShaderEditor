package de.markusfisch.android.shadereditor.project.shadertoy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads JSON text into maps, lists, strings, doubles and booleans, in
 * time and memory proportional to the text. A key that is there twice
 * has its last value.
 */
final class Json {
	/** The value null. */
	static final Object NULL = new Object();

	/** Why a text is not JSON, with the line it stops being it. */
	static final class Malformed extends Exception {
		private Malformed(String message, int line) {
			super("line " + line + ": " + message);
		}
	}

	// Deeper than any shader is; guards the stack.
	private static final int DEEPEST = 32;

	private final String text;
	private int pos;

	private Json(String text) {
		this.text = text;
		pos = !text.isEmpty() && text.charAt(0) == 0xFEFF ? 1 : 0;
	}

	static Object parse(String text) throws Malformed {
		Json json = new Json(text);
		Object value = json.value(0);
		json.skipWhitespace();
		if (json.pos < text.length()) {
			throw json.error("text follows the end of the JSON");
		}
		return value;
	}

	private Object value(int depth) throws Malformed {
		skipWhitespace();
		if (depth > DEEPEST) {
			throw error("it is nested too deeply");
		}
		char c = peek();
		if (c == '{') {
			return object(depth);
		} else if (c == '[') {
			return list(depth);
		} else if (c == '"') {
			return string();
		}
		int start = pos;
		while (pos < text.length() &&
				"+-.0123456789Eabcdeflnrstu".indexOf(text.charAt(pos)) > -1) {
			++pos;
		}
		String token = text.substring(start, pos);
		if (token.equals("true") || token.equals("false")) {
			return Boolean.valueOf(token);
		} else if (token.equals("null")) {
			return NULL;
		} else if (!token.matches("-?[0-9]+(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
			pos = start;
			throw error(start < text.length()
					? "a value is expected"
					: "the text ends in the middle");
		}
		return Double.parseDouble(token);
	}

	private Map<String, Object> object(int depth) throws Malformed {
		Map<String, Object> object = new LinkedHashMap<>();
		++pos;
		if (consume('}')) {
			return object;
		}
		do {
			skipWhitespace();
			if (peek() != '"') {
				throw error("a key in double quotes is expected");
			}
			String key = string();
			if (!consume(':')) {
				throw error("':' is expected after a key");
			}
			object.put(key, value(depth + 1));
		} while (consume(','));
		if (!consume('}')) {
			throw error("',' or '}' is expected");
		}
		return object;
	}

	private List<Object> list(int depth) throws Malformed {
		List<Object> list = new ArrayList<>();
		++pos;
		if (consume(']')) {
			return list;
		}
		do {
			list.add(value(depth + 1));
		} while (consume(','));
		if (!consume(']')) {
			throw error("',' or ']' is expected");
		}
		return list;
	}

	private String string() throws Malformed {
		StringBuilder sb = new StringBuilder();
		++pos;
		while (pos < text.length()) {
			char c = text.charAt(pos++);
			if (c == '"') {
				return sb.toString();
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
				throw error("a string has an escape that JSON does not have");
			}
		}
		throw error("the text ends in the middle of a string");
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
		while (pos < text.length() &&
				" \t\n\r".indexOf(text.charAt(pos)) > -1) {
			++pos;
		}
	}

	private Malformed error(String message) {
		int line = 1;
		for (int i = 0, end = Math.min(pos, text.length()); i < end; ++i) {
			if (text.charAt(i) == '\n') {
				++line;
			}
		}
		return new Malformed(message, line);
	}
}
