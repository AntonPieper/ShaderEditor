package de.markusfisch.android.shadereditor.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.Style;
import de.markusfisch.android.shadereditor.syntax.Tokens;

public class LexerTest {
	private final Symbols symbols = new Symbols();

	// The rules below are what Mesa's compiler does; see Lexer.
	@Test
	public void aNameAcrossALineContinuationIsOneWord() {
		assertEquals("vec4 ( k2 ) ;", words("vec4(k\\\n2);"));
		assertEquals("float k2 = 2.0 ;", words("float k\\\r\n2 = 2.\\\n0;"));
		RawTokens tokens = lex("a k\\\n2 b");
		assertEquals(2, tokens.start[1]);
		assertEquals(6, tokens.end[1]);
	}

	@Test
	public void operatorsAndCommentMarkersAcrossALineContinuation() {
		assertEquals("a += 2.0 ;", words("a +\\\n= 2.0;"));
		assertEquals("a <<= b", words("a <\\\n<\\\n= b"));
		assertEquals("a b", words("a /\\\n/ c\nb"));
		assertEquals("a b", words("a /\\\n* c *\\\n/ b"));
	}

	@Test
	public void aContinuedLineStartsNoDirective() {
		RawTokens tokens = lex("o \\\n#define Y 2.0\n= 1;");
		assertFalse(tokens.is(1, RawTokens.DIRECTIVE));
		assertEquals("o # define Y 2.0 = 1 ;", words("o \\\n#define Y 2.0\n= 1;"));
	}

	@Test
	public void aBlockCommentDoesNotEndADirective() {
		RawTokens tokens = lex("#define A /* c\n */ 1.0\nx");
		assertEquals("# define A 1.0 x", words("#define A /* c\n */ 1.0\nx"));
		assertTrue(tokens.is(3, RawTokens.DIRECTIVE));
		assertFalse(tokens.is(4, RawTokens.DIRECTIVE));
		assertTrue(lex("/* a\n b */ #define X 1").is(0, RawTokens.DIRECTIVE));
	}

	@Test
	public void aLineCommentGoesOnBehindABackslash() {
		assertEquals("# define X 1.0 y", words("#define X 1.0 // c \\\n + 2.0\ny"));
		assertEquals("", words("// c\\\nconst int A=2;"));
	}

	@Test
	public void directives() {
		RawTokens tokens = lex("x; # define A(b) b##b\n#\nfloat y;");
		assertFalse(tokens.is(2, RawTokens.DIRECTIVE));
		assertEquals("x ; # define A ( b ) b ## b # float y ;",
				words("x; # define A(b) b##b\n#\nfloat y;"));
		tokens = lex("  #define A \\\n 1\nfloat y;");
		assertTrue(tokens.is(0, RawTokens.LINE_START));
		assertTrue(tokens.is(3, RawTokens.DIRECTIVE));
		assertFalse(tokens.is(3, RawTokens.LINE_START));
		assertFalse(tokens.is(4, RawTokens.DIRECTIVE));
	}

	// Mesa takes "##define A 9" for the pasting operator.
	@Test
	public void twoHashesAreOneOperatorAndStartNoDirective() {
		String text = "##define A 9\n#\\\n#define B\n# #x";
		RawTokens tokens = lex(text);
		assertEquals("## define A 9 ## define B # # x", words(text));
		assertFalse(tokens.is(0, RawTokens.DIRECTIVE));
		assertFalse(tokens.is(4, RawTokens.DIRECTIVE));
		assertTrue(tokens.is(7, RawTokens.DIRECTIVE));
	}

	@Test
	public void aQuoteStartsAPathOnlyWhereTheAppReadsOne() {
		assertEquals("# include \"a//b/*c.glsl\" x",
				words("#include \"a//b/*c.glsl\" // n\nx"));
		assertEquals("# include \"a//b y", words("#include \"a//b\ny"));
		assertEquals("# pragma se input \"a//ä.png\" x",
				words("#pragma se input \"a//ä.png\" /* n */ x"));
		RawTokens tokens = lex("x = \"a//b\";");
		assertEquals(RawTokens.INVALID, tokens.kind[2]);
		assertEquals(4, tokens.size);
		// To the compiler a quote in any other line is nothing, and a
		// comment starts behind it: Mesa compiles the A below as 2.
		assertEquals("# define Q \" const int A = 2 ;",
				words("#define Q \"/*\n#define A 9\n*/\nconst int A=2;"));
		assertEquals("# pragma x \" y", words("#pragma x \"// c\ny"));
	}

	@Test
	public void numbersAreThoseOfTheLanguage() {
		assertEquals("1.0f oo", words("1.0foo"));
		assertEquals("1.e5 .5 1e-3F 0x1Fu 1u 1 f",
				words("1.e5 .5 1e-3F 0x1Fu 1u 1f"));
		assertEquals("a . x 1. xx", words("a.x 1.xx"));
		// What is no whole number ends where it stops being one.
		assertEquals("0 9 077 8 0 x 0 xg 1 e + 2 1. e 08.5",
				words("09 0778 0x 0xg 1e+ 2 1.e 08.5"));
		// Only ASCII digits are digits: the engine compiles a space there.
		assertEquals("0xA u", words("0xA１u"));
	}

	// Mesa compiles vec4(0x1e+A) with "const int A = 2;" and
	// "#define A 9" to 32, and vec4(0x1e +A) to 39: the preprocessor
	// takes "0x1e+A" for one number and expands nothing in it.
	@Test
	public void aNameInAPreprocessingNumberIsMarked() {
		assertEquals("A", glued("0x1e+A"));
		assertEquals("", glued("0x1e +A"));
		assertEquals("oo", glued("1.0foo"));
		assertEquals("A", glued("1.5.A"));
		assertEquals("e B", glued("1.5e+B"));
		assertEquals("", glued("v.x+A 2e3+A 1.0+e x1e+A"));
		assertEquals("x", glued("0x;x"));
	}

	// Mesa: "#define F\(newline)(x) x" takes an argument, and
	// "#define F  (x) x" does not.
	@Test
	public void aContinuationIsNoSpaceBetweenTokens() {
		assertFalse(lex("#define F\\\n(x) x").is(3, RawTokens.SPACED));
		assertTrue(lex("#define F  (x) x").is(3, RawTokens.SPACED));
		assertTrue(lex("#define F/**/(x) x").is(3, RawTokens.SPACED));
		assertTrue(lex("#define F\\\n (x) x").is(3, RawTokens.SPACED));
		RawTokens tokens = lex(" a b(c)\nd");
		assertTrue(tokens.is(0, RawTokens.SPACED));
		assertTrue(tokens.is(1, RawTokens.SPACED));
		assertFalse(tokens.is(2, RawTokens.SPACED));
		assertTrue(tokens.is(5, RawTokens.SPACED));
	}

	@Test
	public void whatACompilerCannotReadIsWhiteSpace() {
		assertEquals("k r", words("kär"));
		assertEquals("a b", words("a b\u0000"));
		assertEquals("@ $ \\ x", words("@$\\ x"));
		// Behind a backslash that ends a line of code it is left out.
		assertEquals("k2", words("k\\ \n2"));
		// In a comment it is a space, and the comment ends with its
		// line: Mesa compiles the A below.
		assertEquals("const int A = 2 ;", words("// c\\ä\nconst int A=2;"));
		assertEquals("b", words("/* *\\ä\n/ a */ b"));
	}

	@Test
	public void lineEnds() {
		// A line feed and a carriage return, in either order, end one line.
		assertEquals("k2", words("k\\\n\r2"));
		assertEquals("k2", words("k\\\r\n2"));
		assertEquals("k 2", words("k\\\n\n2"));
		// The engine compiles the text with a line end behind it.
		assertEquals("}", words("}\\"));
	}

	@Test
	public void theSameWordIsTheSameSymbol() {
		RawTokens tokens = lex("abc abc ab\\\nc abd ( (");
		assertEquals(tokens.symbol[0], tokens.symbol[1]);
		assertEquals(tokens.symbol[0], tokens.symbol[2]);
		assertTrue(tokens.symbol[0] != tokens.symbol[3]);
		assertEquals(Words.LEFT_PAREN, tokens.symbol[4]);
		assertEquals(tokens.symbol[4], tokens.symbol[5]);
		Symbols other = new Symbols();
		assertEquals(Words.SEMICOLON, other.of(";", 0, 1));
		// A table counts what it holds, for who keeps it to renew it.
		int before = other.characters();
		other.of("abcd", 0, 4);
		other.of("abcd", 0, 4);
		assertEquals(before + 4, other.characters());
	}

	@Test
	public void anEmptyBufferTakesTokens() {
		RawTokens tokens = new RawTokens();
		tokens.trim();
		assertTrue(new Lexer("x", 0, symbols, tokens).next());
		tokens.truncate(0);
		tokens.trim();
		assertTrue(new Lexer("y", 0, symbols, tokens).next());
	}

	@Test
	public void tokensLieInOrderInsideAnyText() {
		Random random = new Random(1);
		for (String text : TreeTexts.own()) {
			assertInOrder(text);
			for (int i = 0; i < 40; ++i) {
				assertInOrder(text = TreeTexts.damaged(text, random));
			}
		}
		for (int i = 0; i < 2000; ++i) {
			assertInOrder(TreeTexts.soup(random, 1 + random.nextInt(30)));
		}
	}

	// On the app's own shaders the editor's tokenizer and this one agree
	// on what is white space or a comment, on every name and number, and
	// on what belongs to a directive.
	@Test
	public void agreesWithTheEditorsTokenizer() {
		for (String text : TreeTexts.own()) {
			Tokens editor = GlslLexer.lex(text);
			StringBuilder theirs = new StringBuilder();
			for (int i = 0; i < editor.size(); ++i) {
				Style style = editor.style(i);
				if (style == Style.COMMENT) {
					continue;
				}
				boolean word = style != Style.OPERATOR && style != Style.INVALID;
				for (int at = editor.start(i); at < editor.end(i); ++at) {
					theirs.append(at).append(word && at > editor.start(i) ? '-' : ' ')
							.append(editor.inDirective(i) ? '#' : ' ');
				}
			}
			RawTokens tokens = lex(text);
			StringBuilder ours = new StringBuilder();
			for (int i = 0; i < tokens.size; ++i) {
				boolean word = tokens.kind[i] != RawTokens.PUNCTUATOR &&
						tokens.kind[i] != RawTokens.INVALID;
				for (int at = tokens.start[i]; at < tokens.end[i]; ++at) {
					ours.append(at).append(word && at > tokens.start[i] ? '-' : ' ')
							.append(tokens.is(i, RawTokens.DIRECTIVE) ? '#' : ' ');
				}
			}
			assertEquals(theirs.toString(), ours.toString());
		}
	}

	// What makes a piece of a tree reusable: its tokens depend on the
	// text only as far as it was looked at.
	@Test
	public void theTokensDependOnlyOnWhatWasLookedAt() {
		Random random = new Random(2);
		for (int round = 0; round < 6000; ++round) {
			String text = TreeTexts.soup(random, 1 + random.nextInt(12));
			RawTokens tokens = new RawTokens();
			Lexer lexer = new Lexer(text, 0, symbols, tokens);
			int count = random.nextInt(6);
			for (int i = 0; i < count && lexer.next(); ++i) {
				// Read on.
			}
			if (lexer.reach() > text.length()) {
				// It looked at the end of the text.
				continue;
			}
			String other = text.substring(0, lexer.reach()) +
					TreeTexts.soup(random, 3);
			RawTokens again = new RawTokens();
			Lexer second = new Lexer(other, 0, symbols, again);
			for (int i = 0; i < tokens.size; ++i) {
				assertTrue(text + "\n---\n" + other, second.next());
			}
			assertEquals(text + "\n---\n" + other, spelled(tokens), spelled(again));
		}
	}

	// A piece of the tree ends behind a ";" or "}": what follows must
	// not matter to it.
	@Test
	public void aTokenThatCannotGrowLooksNoFurther() {
		for (String end : new String[]{";", "}", ")", "{", ","}) {
			RawTokens tokens = new RawTokens();
			Lexer lexer = new Lexer("x" + end + "\\\n".repeat(1000) + "y", 0,
					symbols, tokens);
			lexer.next();
			lexer.next();
			assertEquals(end, 2, lexer.reach());
		}
	}

	// And where a piece may start: behind a ";" or "}" outside a
	// directive the rest reads the same.
	@Test
	public void readingStartsBehindTheEndOfAnItem() {
		Random random = new Random(3);
		int starts = 0;
		for (int round = 0; round < 6000; ++round) {
			String text = TreeTexts.soup(random, 1 + random.nextInt(16));
			RawTokens whole = lex(text);
			for (int i = 0; i < whole.size; ++i) {
				if (whole.is(i, RawTokens.DIRECTIVE) ||
						(whole.symbol[i] != Words.SEMICOLON &&
								whole.symbol[i] != Words.RIGHT_BRACE)) {
					continue;
				}
				++starts;
				RawTokens rest = new RawTokens();
				Lexer lexer = new Lexer(text, whole.end[i], symbols, rest);
				while (lexer.next()) {
					// Read on.
				}
				assertEquals(text, whole.size - i - 1, rest.size);
				for (int k = 0; k < rest.size; ++k) {
					int at = i + 1 + k;
					assertEquals(text, whole.start[at], rest.start[k] + whole.end[i]);
					assertEquals(text, whole.symbol[at], rest.symbol[k]);
					assertEquals(text, whole.flags[at], rest.flags[k]);
					assertEquals(text, whole.kind[at], rest.kind[k]);
				}
			}
		}
		assertTrue(starts > 2000);
	}

	private RawTokens lex(String text) {
		RawTokens tokens = new RawTokens();
		Lexer lexer = new Lexer(text, 0, symbols, tokens);
		while (lexer.next()) {
			// Read on.
		}
		return tokens;
	}

	private String words(String text) {
		RawTokens tokens = lex(text);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.size; ++i) {
			sb.append(i > 0 ? " " : "").append(symbols.name(tokens.symbol[i]));
		}
		return sb.toString();
	}

	// The names the preprocessor does not expand, as they are spelled.
	private String glued(String text) {
		RawTokens tokens = lex(text);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.size; ++i) {
			if (tokens.kind[i] == RawTokens.GLUED_NAME) {
				sb.append(sb.length() > 0 ? " " : "")
						.append(symbols.name(tokens.symbol[i]));
			}
		}
		return sb.toString();
	}

	private String spelled(RawTokens tokens) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.size; ++i) {
			sb.append(tokens.start[i]).append(':').append(tokens.end[i]).append(':')
					.append(tokens.flags[i]).append(':').append(tokens.kind[i])
					.append(':').append(symbols.name(tokens.symbol[i])).append(' ');
		}
		return sb.toString();
	}

	private void assertInOrder(String text) {
		RawTokens tokens = lex(text);
		int last = 0;
		for (int i = 0; i < tokens.size; ++i) {
			assertTrue(text, tokens.start[i] >= last);
			assertTrue(text, tokens.end[i] > tokens.start[i]);
			last = tokens.end[i];
		}
		assertTrue(text, last <= text.length());
	}
}
