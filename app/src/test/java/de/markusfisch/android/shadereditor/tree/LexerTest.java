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

	@Test
	public void aQuoteStartsAPathOnlyInADirective() {
		assertEquals("# include \"a//b/*c.glsl\" x", words("#include \"a//b/*c.glsl\" // n\nx"));
		assertEquals("# include \"a//b y", words("#include \"a//b\ny"));
		RawTokens tokens = lex("x = \"a//b\";");
		assertEquals(RawTokens.INVALID, tokens.kind[2]);
		assertEquals(4, tokens.size);
	}

	@Test
	public void numbers() {
		assertEquals("1.0f oo", words("1.0foo"));
		assertEquals("1.e5 .5 1e-3F 0x1Fu 1u 1 f 0x g", words("1.e5 .5 1e-3F 0x1Fu 1u 1f 0xg"));
		assertEquals("a . x 1. xx", words("a.x 1.xx"));
		assertEquals(RawTokens.INVALID, lex("1e+ 2").kind[0]);
	}

	@Test
	public void whatACompilerCannotReadIsWhiteSpace() {
		assertEquals("k r", words("kär"));
		assertEquals("a b", words("a b\u0000"));
		// Behind a backslash that ends a line it is left out.
		assertEquals("k2", words("k\\ \n2"));
		assertEquals("@ $ \\ x", words("@$\\ x"));
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
		for (int round = 0; round < 3000; ++round) {
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

	// And where a piece may start: behind any token outside a directive
	// the rest reads the same.
	@Test
	public void readingStartsBehindAnyTokenOutsideADirective() {
		Random random = new Random(3);
		for (int round = 0; round < 3000; ++round) {
			String text = TreeTexts.soup(random, 1 + random.nextInt(12));
			RawTokens whole = lex(text);
			for (int i = 0; i < whole.size; ++i) {
				if (whole.is(i, RawTokens.DIRECTIVE)) {
					continue;
				}
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
				}
			}
		}
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

	private String spelled(RawTokens tokens) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < tokens.size; ++i) {
			sb.append(tokens.start[i]).append(':').append(tokens.end[i]).append(':')
					.append(tokens.flags[i]).append(':')
					.append(symbols.name(tokens.symbol[i])).append(' ');
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
