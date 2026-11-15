package de.markusfisch.android.shadereditor.spike;

import java.util.Arrays;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * SPIKE, throwaway: the code of a GLSL text as a parser wants it, made
 * line by line with the production lexer. Code tokens are kept with
 * absolute offsets and their names as numbers; comments and directives
 * are kept beside them. Which branch of a conditional is active is
 * decided from the macros the text itself defines and the ones the host
 * always defines; tokens of the other branches are marked inactive.
 */
final class TokenStream {
	static final int IDENT = 1;
	static final int NUMBER = 2;
	static final int TYPE = 3;
	static final int KEYWORD = 4;
	static final int PUNCT = 5;
	static final int BOOL = 6;
	static final int OTHER = 7;
	static final int KIND_MASK = 7;
	/** First code token on its line. */
	static final int LINE_FIRST = 8;
	/** In a branch of a conditional that is not compiled. */
	static final int INACTIVE = 16;

	static final int D_OTHER = 0;
	static final int D_IF = 1;
	static final int D_ELSE = 2;
	static final int D_ENDIF = 3;
	static final int D_DEFINE = 4;
	static final int D_INCLUDE = 5;
	static final int D_PRAGMA = 6;

	final String source;
	final Names names;
	// Whether the source is not the one the names were made for.
	private boolean foreign;
	final int[] lineStarts;
	int size;
	int[] starts = new int[1024];
	int[] ends = new int[1024];
	byte[] kinds = new byte[1024];
	int[] ids = new int[1024];

	// Comments: offsets, and whether code stands before one on its line.
	int comments;
	int[] commentStarts = new int[64];
	int[] commentEnds = new int[64];
	boolean[] commentAfterCode = new boolean[64];

	// Directives: kind, the offsets of the whole logical line, the code
	// token it stands before, its line, and for a define the name and
	// where its body starts.
	int directives;
	int[] directiveKinds = new int[32];
	int[] directiveStarts = new int[32];
	int[] directiveEnds = new int[32];
	int[] directiveTokens = new int[32];
	int[] directiveLines = new int[32];
	int[] directiveNames = new int[32];
	int[] directiveBodies = new int[32];
	boolean[] directiveActive = new boolean[32];

	private TokenStream(String source, int[] lineStarts) {
		this.source = source;
		this.lineStarts = lineStarts;
		names = new Names(source);
	}

	private TokenStream(String text, Names names) {
		source = text;
		lineStarts = new int[]{0};
		this.names = names;
		foreign = true;
	}

	private TokenStream(TokenStream parent, int[] lineStarts) {
		source = parent.source;
		this.lineStarts = lineStarts;
		names = parent.names;
	}

	static TokenStream of(Text text) {
		Syntax syntax = GlslSyntax.INSTANCE;
		int lines = text.lineCount();
		int[] lineStarts = new int[lines];
		TokenStream out = new TokenStream(text.toString(), lineStarts);
		LineTokens tokens = new LineTokens();
		Conditionals conditionals = new Conditionals();
		int state = Syntax.TEXT_START;
		int directive = -1;
		int word = 0;
		for (int n = 0; n < lines; ++n) {
			String line = text.line(n);
			int lineStart = text.lineStart(n);
			lineStarts[n] = lineStart;
			// The two lowest bits of a state: inside a comment.
			boolean inComment = (state & 3) != 0;
			state = syntax.lexLine(line, n < lines - 1, state, tokens);
			boolean code = false;
			for (int i = 0, count = tokens.size(); i < count; ++i) {
				int start = lineStart + tokens.start(i);
				int end = lineStart + tokens.end(i);
				Style style = tokens.style(i);
				if (tokens.inDirective(i)) {
					if (style == Style.DIRECTIVE &&
							line.charAt(tokens.start(i)) == '#') {
						if (directive >= 0) {
							conditionals.apply(out, directive);
						}
						directive = out.addDirective(start, end, n);
						word = 0;
					} else if (directive >= 0) {
						out.directiveEnds[directive] = end;
						if (style != Style.COMMENT) {
							out.directiveWord(directive, ++word, start, end,
									conditionals);
						}
					}
					continue;
				}
				if (directive >= 0) {
					conditionals.apply(out, directive);
					directive = -1;
				}
				if (style == Style.COMMENT) {
					// A comment that goes on from the line before
					// extends the last one.
					if (i == 0 && tokens.start(i) == 0 && out.comments > 0 &&
							inComment) {
						out.commentEnds[out.comments - 1] = end;
					} else {
						out.addComment(start, end, code);
					}
					continue;
				}
				if (style != Style.NUMBER && out.size > 0 && end - start == 1 &&
						out.ends[out.size - 1] == start &&
						out.kind(out.size - 1) == NUMBER &&
						(line.charAt(tokens.start(i)) | 0x20) == 'u') {
					// The lexer leaves the "u" of "0x1fu" outside the
					// number; put it back.
					out.ends[out.size - 1] = end;
					continue;
				}
				out.add(start, end, style, !code, conditionals.active);
				code = true;
			}
		}
		if (directive >= 0) {
			conditionals.apply(out, directive);
		}
		return out;
	}

	/**
	 * Return the code between two offsets of parent, such as the body
	 * of a macro, as a stream of its own with the same names.
	 */
	static TokenStream fragment(TokenStream parent, int from, int to) {
		TokenStream out = new TokenStream(parent, parent.lineStarts);
		de.markusfisch.android.shadereditor.syntax.Tokens tokens =
				de.markusfisch.android.shadereditor.syntax.GlslLexer.lex(
						parent.source.substring(from, to));
		for (int i = 0, n = tokens.size(); i < n; ++i) {
			Style style = tokens.style(i);
			if (style != Style.COMMENT) {
				out.add(from + tokens.start(i), from + tokens.end(i), style,
						false, true);
			}
		}
		return out;
	}

	/**
	 * Return the code of text, which is not part of parent's source
	 * (a line typed since), as a stream with parent's names.
	 */
	static TokenStream ofText(TokenStream parent, String text) {
		TokenStream out = new TokenStream(text, parent.names);
		de.markusfisch.android.shadereditor.syntax.Tokens tokens =
				de.markusfisch.android.shadereditor.syntax.GlslLexer.lex(text);
		for (int i = 0, n = tokens.size(); i < n; ++i) {
			Style style = tokens.style(i);
			if (style == Style.COMMENT || tokens.inDirective(i)) {
				continue;
			}
			out.add(tokens.start(i), tokens.end(i), style, false, true);
			int at = out.size - 1;
			if (out.ids[at] >= 0) {
				// The word was interned by its place in the wrong
				// source; give it its number by its text.
				out.ids[at] = parent.names.intern(text.substring(
						tokens.start(i), tokens.end(i)));
			}
		}
		return out;
	}

	int kind(int i) {
		return kinds[i] & KIND_MASK;
	}

	boolean lineFirst(int i) {
		return (kinds[i] & LINE_FIRST) != 0;
	}

	boolean inactive(int i) {
		return (kinds[i] & INACTIVE) != 0;
	}

	char charAt(int i) {
		return source.charAt(starts[i]);
	}

	String text(int i) {
		return source.substring(starts[i], ends[i]);
	}

	int lineOf(int offset) {
		int low = 0;
		int high = lineStarts.length - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (lineStarts[mid] <= offset) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return Math.max(0, high);
	}

	/** Index of the first token that starts at or after offset. */
	int tokenAt(int offset) {
		int low = 0;
		int high = size - 1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (starts[mid] < offset) {
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return low;
	}

	private void add(int start, int end, Style style, boolean first,
			boolean active) {
		if (size == starts.length) {
			int capacity = size * 2;
			starts = Arrays.copyOf(starts, capacity);
			ends = Arrays.copyOf(ends, capacity);
			kinds = Arrays.copyOf(kinds, capacity);
			ids = Arrays.copyOf(ids, capacity);
		}
		int kind;
		int id = -1;
		switch (style) {
			case NUMBER:
				kind = NUMBER;
				break;
			case OPERATOR:
				kind = PUNCT;
				break;
			case INVALID:
				kind = OTHER;
				break;
			case BOOL:
				kind = BOOL;
				id = foreign ? 0 : names.intern(start, end);
				break;
			case PRIMITIVE:
			case TYPE_VECTOR:
			case TYPE_MATRIX:
			case TYPE_SAMPLER:
				kind = TYPE;
				id = foreign ? 0 : names.intern(start, end);
				break;
			case KEYWORD:
			case CONTROL_FLOW:
			case TYPE_QUALIFIER:
			case PRECISION:
				kind = KEYWORD;
				id = foreign ? 0 : names.intern(start, end);
				break;
			default:
				kind = IDENT;
				id = foreign ? 0 : names.intern(start, end);
				break;
		}
		starts[size] = start;
		ends[size] = end;
		kinds[size] = (byte) (kind | (first ? LINE_FIRST : 0) |
				(active ? 0 : INACTIVE));
		ids[size] = id;
		++size;
	}

	private void addComment(int start, int end, boolean afterCode) {
		if (comments == commentStarts.length) {
			int capacity = comments * 2;
			commentStarts = Arrays.copyOf(commentStarts, capacity);
			commentEnds = Arrays.copyOf(commentEnds, capacity);
			commentAfterCode = Arrays.copyOf(commentAfterCode, capacity);
		}
		commentStarts[comments] = start;
		commentEnds[comments] = end;
		commentAfterCode[comments] = afterCode;
		++comments;
	}

	private int addDirective(int start, int end, int line) {
		if (directives == directiveKinds.length) {
			int capacity = directives * 2;
			directiveKinds = Arrays.copyOf(directiveKinds, capacity);
			directiveStarts = Arrays.copyOf(directiveStarts, capacity);
			directiveEnds = Arrays.copyOf(directiveEnds, capacity);
			directiveTokens = Arrays.copyOf(directiveTokens, capacity);
			directiveLines = Arrays.copyOf(directiveLines, capacity);
			directiveNames = Arrays.copyOf(directiveNames, capacity);
			directiveBodies = Arrays.copyOf(directiveBodies, capacity);
			directiveActive = Arrays.copyOf(directiveActive, capacity);
		}
		directiveKinds[directives] = D_OTHER;
		directiveStarts[directives] = start;
		directiveEnds[directives] = end;
		directiveTokens[directives] = size;
		directiveLines[directives] = line;
		directiveNames[directives] = -1;
		directiveBodies[directives] = -1;
		directiveActive[directives] = true;
		return directives++;
	}

	// The n-th word of directive d after its "#", comments not counted.
	private void directiveWord(int d, int n, int start, int end,
			Conditionals conditionals) {
		if (n == 1) {
			int id = names.intern(start, end);
			conditionals.command = id;
			conditionals.argument = -1;
			conditionals.words = 0;
			directiveKinds[d] = id == Names.IF || id == Names.IFDEF ||
					id == Names.IFNDEF ? D_IF
					: id == Names.ELSE || id == Names.ELIF ? D_ELSE
					: id == Names.ENDIF ? D_ENDIF
					: id == Names.DEFINE ? D_DEFINE
					: id == Names.INCLUDE ? D_INCLUDE
					: id == Names.PRAGMA ? D_PRAGMA : D_OTHER;
			return;
		}
		++conditionals.words;
		if (n == 2) {
			char c = source.charAt(start);
			if (Character.isLetter(c) || c == '_') {
				directiveNames[d] = names.intern(start, end);
				conditionals.argument = directiveNames[d];
			} else if (c == '0' && end - start == 1) {
				conditionals.argument = -2;
			} else if (c == '"') {
				directiveBodies[d] = start;
			}
			if (directiveKinds[d] == D_DEFINE) {
				directiveBodies[d] = end;
			}
		}
	}

	/**
	 * Which branch of each conditional is compiled, as far as the text
	 * tells: "#ifdef X" by whether the text defined X above or the host
	 * always does, "#if 0" never, any other "#if" always, and of the
	 * rest of a conditional the first branch after none was taken.
	 */
	private static final class Conditionals {
		boolean active = true;
		int command = -1;
		int argument;
		int words;
		private boolean[] defined = new boolean[256];
		// Per open conditional: whether the code around it is active,
		// and whether a branch was taken.
		private boolean[] outer = new boolean[16];
		private boolean[] taken = new boolean[16];
		private int depth;

		Conditionals() {
			define(Names.GL_ES);
			define(Names.SHADER_EDITOR);
			define(Names.GL_FRAGMENT_PRECISION_HIGH);
		}

		private void define(int id) {
			if (id >= defined.length) {
				defined = Arrays.copyOf(defined, id * 2);
			}
			defined[id] = true;
		}

		private boolean isDefined(int id) {
			return id >= 0 && id < defined.length && defined[id];
		}

		void apply(TokenStream out, int d) {
			out.directiveActive[d] = active;
			int id = command;
			if (id == Names.DEFINE) {
				if (active && argument >= 0) {
					define(argument);
				}
			} else if (id == Names.UNDEF) {
				if (active && argument >= 0 && argument < defined.length) {
					defined[argument] = false;
				}
			} else if (id == Names.IF || id == Names.IFDEF ||
					id == Names.IFNDEF) {
				if (depth == outer.length) {
					outer = Arrays.copyOf(outer, depth * 2);
					taken = Arrays.copyOf(taken, depth * 2);
				}
				boolean take = id == Names.IFDEF ? isDefined(argument)
						: id == Names.IFNDEF ? !isDefined(argument)
						: !(argument == -2 && words == 1);
				outer[depth] = active;
				taken[depth] = take;
				++depth;
				active = active && take;
				out.directiveActive[d] = outer[depth - 1];
			} else if (id == Names.ELSE || id == Names.ELIF) {
				if (depth > 0) {
					boolean take = !taken[depth - 1];
					taken[depth - 1] |= take;
					active = outer[depth - 1] && take;
					out.directiveActive[d] = outer[depth - 1];
				}
			} else if (id == Names.ENDIF) {
				if (depth > 0) {
					--depth;
					active = outer[depth];
					out.directiveActive[d] = active;
				}
			}
			command = -1;
		}
	}
}
