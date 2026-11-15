package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.syntax.GlslStatement.Token;

public class GlslStatementTest {
	/** Return the tokens of text that are not comments as a statement. */
	static GlslStatement statement(String text) {
		GlslStatement statement = new GlslStatement();
		LineTokens tokens = new LineTokens();
		int state = GlslLexer.TEXT_START;
		for (int start = 0, length = text.length(); start <= length; ) {
			int end = text.indexOf('\n', start);
			end = end < 0 ? length : end;
			String line = text.substring(start, end);
			state = GlslLexer.lexLine(line, end < length, state, tokens);
			for (int i = 0; i < tokens.size(); ++i) {
				Style style = tokens.style(i);
				if (style != Style.COMMENT) {
					statement.add(line, tokens.start(i), tokens.end(i),
							start + tokens.start(i), Token.of(style, line,
									tokens.start(i), tokens.end(i)));
				}
			}
			start = end + 1;
		}
		return statement;
	}

	@Test
	public void readsAsWrittenHoweverItWasLaidOut() {
		String text = "uniform   vec3\n\tlight [ 2 ] ;";
		GlslStatement statement = statement(text);
		assertEquals(7, statement.size());
		assertEquals("uniform vec3 light [ 2 ] ;", statement.text(0, 7));
		assertEquals("vec3 light", statement.text(1, 3));
		assertEquals("", statement.text(3, 3));
		assertEquals("vec3 a[2];", statement("vec3 a[2];").text(0, 6));
		for (int i = 0; i < statement.size(); ++i) {
			assertEquals(statement.text(i), text.substring(
					statement.start(i), statement.end(i)));
		}
		assertEquals(Token.UNIFORM, statement.kind(0));
		assertEquals(Token.TYPE, statement.kind(1));
		assertEquals(Token.NAME, statement.kind(2));
		assertEquals('[', statement.punct(3));
		assertEquals(0, statement.punct(2));
		assertTrue(statement.is(6, ';'));
		assertTrue(statement.is(2, "light"));
		assertFalse(statement.is(2, "lights"));
		assertTrue(statement.has(Token.NUMBER));
		assertFalse(statement.has(Token.STRUCT));
	}

	@Test
	public void droppingTheFirstTokensLeavesTheOthersAsTheyWere() {
		String text = "float a\nvec3  b = vec3(1.0)";
		GlslStatement statement = statement(text);
		statement.drop(2);
		assertEquals(7, statement.size());
		assertEquals("vec3 b = vec3(1.0)", statement.text(0, 7));
		assertEquals("b", statement.text(1));
		for (int i = 0; i < statement.size(); ++i) {
			assertEquals(statement.text(i), text.substring(
					statement.start(i), statement.end(i)));
		}
		statement.drop(0);
		assertEquals(7, statement.size());
		statement.drop(9);
		assertTrue(statement.isEmpty());
		assertEquals("", statement.text(0, 0));
	}

	@Test
	public void theHeadOfAFunction() {
		assertEquals(1, name("void main("));
		assertEquals(3, name("const highp vec3 f(vec3 p"));
		assertEquals(4, name("Ray[2] rays(float t)"));
		assertEquals(1, name("Ray make()"));
		// No type, no name, no "(": not a head.
		assertEquals(-1, name("main("));
		assertEquals(-1, name("float ("));
		assertEquals(-1, name("float f"));
		assertEquals(-1, name("layout(location = 0) out vec4 colour"));
		assertEquals(-1, name("struct S ("));
		assertEquals(-1, name(""));
		// Only the tokens asked about count.
		assertEquals(-1, statement("void main(").functionName(2));
		// Words before the type qualify it, known to GLSL or not.
		assertEquals(2, name("inline float2 f("));
		assertEquals(3, name("EXPORT static float f(float x)"));
		assertEquals(-1, name("precision highp float f("));
	}

	@Test
	public void theNameAStatementStartsToDeclare() {
		assertEquals(1, declared("float a"));
		assertEquals(3, declared("uniform PREC float speed"));
		assertEquals(3, declared("static const float x = 1.0"));
		assertEquals(4, declared("float[2] a[3], b"));
		assertEquals(8, declared("layout(location = 0) out vec4 colour"));
		assertEquals(1, declared("Ray r"));
		// A type alone, a name alone, and qualifiers declare nothing.
		assertEquals(-1, declared("float"));
		assertEquals(-1, declared("const highp float"));
		assertEquals(-1, declared("x = 1.0"));
		assertEquals(-1, declared("uniform speed"));
		assertEquals(-1, declared("f(x)"));
		assertEquals(-1, declared(") float x"));
		// Only the tokens asked about count.
		GlslStatement statement = statement("f(in PREC float x, float y)");
		assertEquals(5, statement.name(2, 6));
		assertEquals(8, statement.name(7, 9));
		assertEquals(-1, statement.name(2, 5));
	}

	private static int declared(String text) {
		GlslStatement statement = statement(text);
		return statement.name(0, statement.size());
	}

	private static int name(String text) {
		GlslStatement statement = statement(text);
		return statement.functionName(statement.size());
	}

	@Test
	public void whatStandsBehindBrackets() {
		GlslStatement statement = statement(
				"layout(std140, binding = f(1)) uniform float a[2][3], b");
		int size = statement.size();
		assertEquals("float", statement.text(statement.afterQualifiers(size)));
		assertEquals(",", statement.text(statement.afterBrackets(14, size)));
		assertEquals("uniform", statement.text(
				statement.afterClosing(1, size)));
		// A bracket that nothing closes reaches to the end.
		assertEquals(3, statement("f ( a").afterClosing(1, 3));
		assertEquals(",", statement.text(statement.afterDeclarator(13, size)));
		assertEquals(size, statement.afterDeclarator(21, size));
	}

	@Test
	public void aLineThatStartsAnotherStatement() {
		// A keyword of a declaration, unless keywords lead up to it.
		assertTrue(endsBefore("uniform vec3 a", "uniform"));
		assertTrue(endsBefore("float a = 1.0", "const"));
		assertFalse(endsBefore("uniform", "highp"));
		assertFalse(endsBefore("layout(location = 0) out", "highp"));
		// Behind nothing but a type, a name is what it declares.
		assertFalse(endsBefore("Ray", "r"));
		assertFalse(endsBefore("const float[2]", "r"));
		assertTrue(endsBefore("Ray", "float"));
		// Behind a name, a number or a closing bracket nothing goes on.
		assertTrue(endsBefore("float c", "vec3"));
		assertTrue(endsBefore("float c", "d"));
		assertTrue(endsBefore("float c = 1.0", "d"));
		assertTrue(endsBefore("float c[2]", "d"));
		assertTrue(endsBefore("vec3 d(vec3 p)", "void"));
		assertTrue(endsBefore("struct S { float a; }", "S"));
		// A type behind a type is another statement, whatever leads
		// up to the first: a name is what a type declares.
		assertTrue(endsBefore("precision mediump float", "void"));
		assertFalse(endsBefore("precision mediump float", "x"));
		assertTrue(endsBefore("uniform PREC float", "vec3"));
		assertFalse(endsBefore("uniform PREC float", "speed"));
		assertTrue(endsBefore("uniform PREC float speed", "vec3"));
		assertTrue(endsBefore("COMPAT_VARYING", "vec4"));
		assertFalse(endsBefore("uniform", "PREC"));
		// Behind an operator it does.
		assertFalse(endsBefore("float c =", "d"));
		assertFalse(endsBefore("float c = a +", "vec3"));
		assertFalse(endsBefore("float c,", "d"));
		// And what is no word starts nothing.
		assertFalse(endsBefore("float c", "="));
		assertFalse(endsBefore("float c", "1.0"));
	}

	private static boolean endsBefore(String text, String next) {
		return statement(text).endsBefore(statement(next).kind(0));
	}

	@Test
	public void keepsWhatItHasOfMoreThanADeclaration() {
		GlslStatement statement = new GlslStatement();
		for (int i = 0; i < 5000; ++i) {
			statement.add("a,", i % 2, i % 2 + 1, i, i % 2 == 0
					? Token.NAME : Token.PUNCT);
		}
		assertEquals(2048, statement.size());
		assertEquals("a,a,", statement.text(2044, 2048));
		statement.clear();
		char[] name = new char[20000];
		java.util.Arrays.fill(name, 'x');
		statement.add(new String(name), 0, name.length, 0, Token.NAME);
		assertTrue(statement.isEmpty());
	}
}
