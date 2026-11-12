package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The top-level declarations of one text: functions, global variables and
 * uniforms, macros and struct types, in the order they appear.
 *
 * This is a scan of the tokens, not a parse. It tracks braces, brackets
 * and parentheses and reads what is declared outside of them. Both
 * branches of a conditional are read, except code between "#if 0" and its
 * "#elif", "#else" or "#endif".
 */
public final class Outline {
	public enum Kind {
		FUNCTION,
		VARIABLE,
		UNIFORM,
		MACRO,
		TYPE
	}

	/** A declared name and the offsets of its name token. */
	public record Declaration(String name, Kind kind, int start, int end) {
	}

	private final List<Declaration> declarations;

	private Outline(List<Declaration> declarations) {
		this.declarations = Collections.unmodifiableList(declarations);
	}

	public static Outline of(Tokens tokens, CharSequence text) {
		Scan scan = new Scan(tokens, text);
		scan.run();
		return new Outline(scan.declarations);
	}

	public List<Declaration> declarations() {
		return declarations;
	}

	/**
	 * Returns the tokens with every identifier that names a declared type
	 * styled as {@link Style#TYPE}.
	 *
	 * @param tokens The tokens this outline was made of.
	 * @param text   The text they were made of.
	 */
	public Tokens colourTypes(Tokens tokens, CharSequence text) {
		String[] types = new String[declarations.size()];
		int count = 0;
		for (Declaration declaration : declarations) {
			if (declaration.kind == Kind.TYPE) {
				types[count++] = declaration.name;
			}
		}
		if (count == 0) {
			return tokens;
		}
		Arrays.sort(types, 0, count);
		int[] indices = new int[16];
		int retyped = 0;
		for (int i = 0, size = tokens.size(); i < size; ++i) {
			if (tokens.style(i) == Style.IDENTIFIER && GlslWords.contains(
					types, count, text, tokens.start(i), tokens.end(i))) {
				if (retyped == indices.length) {
					indices = Arrays.copyOf(indices, retyped * 2);
				}
				indices[retyped++] = i;
			}
		}
		return retyped == 0
				? tokens
				: tokens.withStyle(indices, retyped, Style.TYPE);
	}

	private static final class Scan {
		private final List<Declaration> declarations = new ArrayList<>();
		private final Tokens tokens;
		private final CharSequence text;
		// Nesting of conditionals, and the level of the "#if 0" that
		// disables code, or -1.
		private int level;
		private int disabled = -1;
		private int braces;
		private int parens;
		private int brackets;
		// State of the current top-level statement.
		private int name = -1;
		private boolean typed;
		private boolean uniform;
		private boolean initializer;
		private boolean struct;
		private boolean function;
		private boolean body;

		private Scan(Tokens tokens, CharSequence text) {
			this.tokens = tokens;
			this.text = text;
		}

		private void run() {
			for (int i = 0, size = tokens.size(); i < size; ) {
				if (tokens.inDirective(i)) {
					i = directive(i);
					continue;
				}
				if (disabled < 0 && tokens.style(i) != Style.COMMENT) {
					code(i);
				}
				++i;
			}
		}

		// Reads the directive that starts at i and returns the index after
		// it.
		private int directive(int i) {
			int end = i + 1;
			while (end < tokens.size() && tokens.inDirective(end) &&
					!isDirectiveStart(end)) {
				++end;
			}
			int command = next(i, end);
			int argument = next(command, end);
			if (command >= end) {
				return end;
			}
			if (is(command, "define")) {
				if (disabled < 0 && argument < end && isWord(argument)) {
					add(argument, Kind.MACRO);
				}
			} else if (is(command, "if") || is(command, "ifdef") ||
					is(command, "ifndef")) {
				++level;
				if (disabled < 0 && is(command, "if") && argument < end &&
						is(argument, "0") && next(argument, end) == end) {
					disabled = level;
				}
			} else if (is(command, "elif") || is(command, "else")) {
				if (disabled == level) {
					disabled = -1;
				}
			} else if (is(command, "endif")) {
				if (disabled == level) {
					disabled = -1;
				}
				level = Math.max(0, level - 1);
			}
			return end;
		}

		private void code(int i) {
			char c = text.charAt(tokens.start(i));
			if (braces > 0) {
				if (c == '{') {
					++braces;
				} else if (c == '}' && --braces == 0 && body) {
					endStatement();
				}
			} else if (parens > 0) {
				if (c == '(') {
					++parens;
				} else if (c == ')') {
					--parens;
				}
			} else if (brackets > 0) {
				if (c == '[') {
					++brackets;
				} else if (c == ']') {
					--brackets;
				}
			} else if (isWord(i)) {
				word(i);
			} else {
				punctuation(c);
			}
		}

		private void word(int i) {
			if (initializer) {
				return;
			}
			Style style = tokens.style(i);
			boolean isName = style == Style.IDENTIFIER || style == Style.TYPE;
			if (struct) {
				struct = false;
				typed = true;
				if (isName) {
					add(i, Kind.TYPE);
				}
			} else if (isName) {
				typed |= name > -1;
				name = i;
			} else {
				struct = is(i, "struct");
				uniform |= is(i, "uniform");
				typed = true;
				name = -1;
			}
		}

		private void punctuation(char c) {
			struct = false;
			switch (c) {
				case '(':
					if (name > -1 && typed && !initializer) {
						add(name, Kind.FUNCTION);
						function = true;
					}
					name = -1;
					parens = 1;
					break;
				case '[':
					declare();
					brackets = 1;
					break;
				case '{':
					braces = 1;
					body = function;
					name = -1;
					break;
				case '=':
					declare();
					initializer = true;
					break;
				case ',':
					declare();
					initializer = false;
					break;
				case ';':
				case '}':
					declare();
					endStatement();
					break;
				default:
					name = -1;
					break;
			}
		}

		private void declare() {
			if (name > -1 && typed && !initializer) {
				add(name, uniform ? Kind.UNIFORM : Kind.VARIABLE);
			}
			name = -1;
		}

		private void endStatement() {
			name = -1;
			typed = false;
			uniform = false;
			initializer = false;
			struct = false;
			function = false;
			body = false;
		}

		private void add(int i, Kind kind) {
			int start = tokens.start(i);
			int end = tokens.end(i);
			declarations.add(new Declaration(
					text.subSequence(start, end).toString(), kind, start, end));
		}

		// Index of the first token after i and before end that is no
		// comment, or end.
		private int next(int i, int end) {
			do {
				++i;
			} while (i < end && tokens.style(i) == Style.COMMENT);
			return Math.min(i, end);
		}

		private boolean isDirectiveStart(int i) {
			return tokens.style(i) == Style.DIRECTIVE &&
					text.charAt(tokens.start(i)) == '#';
		}

		private boolean isWord(int i) {
			char c = text.charAt(tokens.start(i));
			return (Character.isLetter(c) || c == '_') &&
					tokens.style(i) != Style.COMMENT &&
					tokens.style(i) != Style.STRING;
		}

		private boolean is(int i, String word) {
			int start = tokens.start(i);
			int length = word.length();
			if (tokens.end(i) - start != length) {
				return false;
			}
			for (int k = 0; k < length; ++k) {
				if (text.charAt(start + k) != word.charAt(k)) {
					return false;
				}
			}
			return true;
		}
	}
}
