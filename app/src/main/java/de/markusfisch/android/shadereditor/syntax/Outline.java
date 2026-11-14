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
 * "#elif", "#else" or "#endif". That scan is GLSL's; the declarations
 * are what any language may collect, see {@link Syntax#outline}.
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

	/** No declarations. */
	public static final Outline NONE = new Outline(new ArrayList<>());

	private final List<Declaration> declarations;
	// Each name once, sorted, and how many declarations have it.
	final String[] names;
	final int[] counts;

	private Outline(List<Declaration> declarations) {
		this.declarations = Collections.unmodifiableList(declarations);
		String[] names = new String[declarations.size()];
		for (int i = 0; i < names.length; ++i) {
			names[i] = declarations.get(i).name();
		}
		Arrays.sort(names);
		int[] counts = new int[names.length];
		int size = 0;
		for (String name : names) {
			if (size > 0 && names[size - 1].equals(name)) {
				++counts[size - 1];
			} else {
				names[size] = name;
				counts[size++] = 1;
			}
		}
		this.names = Arrays.copyOf(names, size);
		this.counts = Arrays.copyOf(counts, size);
	}

	public static Outline of(Tokens tokens, CharSequence text) {
		Scan scan = new Scan(Integer.MAX_VALUE, Integer.MAX_VALUE);
		for (int i = 0, size = tokens.size(); i < size; ++i) {
			scan.token(text, 0, tokens.start(i), tokens.end(i),
					tokens.style(i), tokens.inDirective(i));
		}
		return scan.outline();
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

	/**
	 * Builds the outline of a text line by line, without the whole text
	 * or all of its tokens.
	 */
	public interface Builder {
		/**
		 * Add the next line, which starts at offset lineStart, with the
		 * tokens the language gave it.
		 */
		void addLine(CharSequence line, int lineStart, LineTokens tokens);

		/** Return the outline of the lines added; call it once, last. */
		Outline build();
	}

	/**
	 * Return a builder of the outline of GLSL, from the tokens
	 * {@link GlslLexer#lexLine} gives for each line. It keeps the
	 * declarations in the order they appear until there are
	 * maxDeclarations of them or the next name would make their names
	 * longer than maxNameChars together; the declarations after that are
	 * left out.
	 */
	public static Builder builder(int maxDeclarations, int maxNameChars) {
		Scan scan = new Scan(maxDeclarations, maxNameChars);
		return new Builder() {
			@Override
			public void addLine(CharSequence line, int lineStart,
					LineTokens tokens) {
				for (int i = 0, size = tokens.size(); i < size; ++i) {
					scan.token(line, lineStart, tokens.start(i),
							tokens.end(i), tokens.style(i),
							tokens.inDirective(i));
				}
			}

			@Override
			public Outline build() {
				return scan.outline();
			}
		};
	}

	// Reads one token after the other; a token is the range [start, end)
	// of text, which starts at offset in the whole text.
	private static final class Scan {
		private static final int OTHER = 0;
		private static final int DEFINE = 1;
		private static final int IF = 2;

		private final List<Declaration> declarations = new ArrayList<>();
		private final int maxDeclarations;
		private final int maxNameChars;
		private int nameChars;
		private boolean full;
		// Nesting of conditionals, and the level of the "#if 0" that
		// disables code, or -1.
		private int level;
		private int disabled = -1;
		private int braces;
		private int parens;
		private int brackets;
		// The directive being read: how many of its tokens after "#" were
		// no comment, what its command was, and whether it is "#if 0" so
		// far.
		private boolean directive;
		private int directiveWords;
		private int command;
		private boolean ifZero;
		// State of the current top-level statement; nameText is null if
		// there is no name.
		private CharSequence nameText;
		private int nameOffset;
		private int nameStart;
		private int nameEnd;
		private boolean typed;
		private boolean uniform;
		private boolean initializer;
		private boolean struct;
		private boolean function;
		private boolean body;

		private Scan(int maxDeclarations, int maxNameChars) {
			this.maxDeclarations = maxDeclarations;
			this.maxNameChars = maxNameChars;
		}

		private Outline outline() {
			endDirective();
			return new Outline(declarations);
		}

		private void token(CharSequence text, int offset, int start, int end,
				Style style, boolean inDirective) {
			if (inDirective) {
				if (!directive || (style == Style.DIRECTIVE &&
						text.charAt(start) == '#')) {
					endDirective();
					directive = true;
					directiveWords = 0;
					command = OTHER;
				} else if (style != Style.COMMENT) {
					directiveWord(text, offset, start, end, style);
				}
				return;
			}
			endDirective();
			if (disabled < 0 && style != Style.COMMENT) {
				code(text, offset, start, end, style);
			}
		}

		private void directiveWord(CharSequence text, int offset, int start,
				int end, Style style) {
			if (++directiveWords == 1) {
				if (is(text, start, end, "define")) {
					command = DEFINE;
				} else if (is(text, start, end, "if") ||
						is(text, start, end, "ifdef") ||
						is(text, start, end, "ifndef")) {
					++level;
					command = is(text, start, end, "if") ? IF : OTHER;
				} else if (is(text, start, end, "elif") ||
						is(text, start, end, "else")) {
					if (disabled == level) {
						disabled = -1;
					}
				} else if (is(text, start, end, "endif")) {
					if (disabled == level) {
						disabled = -1;
					}
					level = Math.max(0, level - 1);
				}
			} else if (directiveWords == 2) {
				if (command == DEFINE && disabled < 0 &&
						isWord(text, start, style)) {
					add(text, offset, start, end, Kind.MACRO);
				}
				ifZero = command == IF && disabled < 0 &&
						is(text, start, end, "0");
			} else {
				ifZero = false;
			}
		}

		private void endDirective() {
			if (directive) {
				directive = false;
				if (ifZero) {
					disabled = level;
					ifZero = false;
				}
			}
		}

		private void code(CharSequence text, int offset, int start, int end,
				Style style) {
			char c = text.charAt(start);
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
			} else if (isWord(text, start, style)) {
				word(text, offset, start, end, style);
			} else {
				punctuation(c);
			}
		}

		private void word(CharSequence text, int offset, int start, int end,
				Style style) {
			if (initializer) {
				return;
			}
			boolean isName = style == Style.IDENTIFIER || style == Style.TYPE;
			if (struct) {
				struct = false;
				typed = true;
				if (isName) {
					add(text, offset, start, end, Kind.TYPE);
				}
			} else if (isName) {
				typed |= nameText != null;
				nameText = text;
				nameOffset = offset;
				nameStart = start;
				nameEnd = end;
			} else {
				struct = is(text, start, end, "struct");
				uniform |= is(text, start, end, "uniform");
				typed = true;
				nameText = null;
			}
		}

		private void punctuation(char c) {
			struct = false;
			switch (c) {
				case '(':
					if (nameText != null && typed && !initializer) {
						addName(Kind.FUNCTION);
						function = true;
					}
					nameText = null;
					parens = 1;
					break;
				case '[':
					declare();
					brackets = 1;
					break;
				case '{':
					braces = 1;
					body = function;
					nameText = null;
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
					nameText = null;
					break;
			}
		}

		private void declare() {
			if (nameText != null && typed && !initializer) {
				addName(uniform ? Kind.UNIFORM : Kind.VARIABLE);
			}
			nameText = null;
		}

		private void endStatement() {
			nameText = null;
			typed = false;
			uniform = false;
			initializer = false;
			struct = false;
			function = false;
			body = false;
		}

		private void addName(Kind kind) {
			add(nameText, nameOffset, nameStart, nameEnd, kind);
		}

		private void add(CharSequence text, int offset, int start, int end,
				Kind kind) {
			int length = end - start;
			if (full || declarations.size() == maxDeclarations ||
					length > maxNameChars - nameChars) {
				full = true;
				return;
			}
			nameChars += length;
			declarations.add(new Declaration(
					text.subSequence(start, end).toString(), kind,
					offset + start, offset + end));
		}

		private static boolean isWord(CharSequence text, int start,
				Style style) {
			char c = text.charAt(start);
			return (Character.isLetter(c) || c == '_') &&
					style != Style.COMMENT &&
					style != Style.STRING;
		}

		private static boolean is(CharSequence text, int start, int end,
				String word) {
			int length = word.length();
			if (end - start != length) {
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
