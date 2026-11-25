package de.markusfisch.android.shadereditor.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeSet;

import de.markusfisch.android.shadereditor.project.DirectiveCases;
import de.markusfisch.android.shadereditor.project.DirectiveOracle;

public class PreprocessorTest {
	// ISO C 6.10.3.5, example 3, without the lines that paste: what
	// every C preprocessor makes of it.
	@Test
	public void expandsAsTheCStandardSays() {
		String defines = "#define x 3\n#define f(a) f(x * (a))\n#undef x\n" +
				"#define x 2\n#define g f\n#define z z[0]\n#define h g(~\n" +
				"#define m(a) a(w)\n#define w 0,1\n#define t(a) a\n";
		assertEquals("f ( 2 * ( y + 1 ) ) + f ( 2 * ( f ( 2 * ( z [ 0 ] ) ) ) ) " +
						"% f ( 2 * ( 0 ) ) + t ( 1 ) ;",
				new Compiled(defines + "f(y+1) + f(f(z)) % t(t(g)(0) + t)(1);")
						.text());
		assertEquals("f ( 2 * ( 2 + ( 3 , 4 ) - 0 , 1 ) ) | f ( 2 * ( ~ 5 ) ) & " +
						"f ( 2 * ( 0 , 1 ) ) ^ m ( 0 , 1 ) ;",
				new Compiled(defines + "g(x+(3,4)-w) | h 5) & m\n(f)^m(m);").text());
	}

	@Test
	public void everyCompiledTokenSaysWhereItIsWritten() {
		Compiled c = new Compiled("#define TEX(d) texture(S, uv + d)\n" +
				"c = TEX(off);");
		assertEquals("c = texture ( S , uv + off ) ;", c.text());
		// "off" is written in the argument: its own raw token, through TEX.
		int off = 8;
		assertEquals("off", c.symbols.name(c.raw.symbol[c.tokens.raw[off]]));
		assertEquals("TEX", c.symbols.name(c.raw.symbol[c.tokens.via[off]]));
		// "uv" is the fifth token of the macro's text.
		int uv = 6;
		int k = -1 - c.tokens.raw[uv];
		assertEquals("TEX", c.symbols.name(c.tokens.macro[k].name));
		assertEquals("uv", c.symbols.name(c.raw.symbol[
				c.tokens.macro[k].bodyAt + c.tokens.bodyIndex[k]]));
		assertEquals(c.tokens.via[off], c.tokens.via[uv]);
		// And what is not out of a macro came through none.
		assertEquals(-1, c.tokens.via[0]);
		assertEquals("c", c.symbols.name(c.raw.symbol[c.tokens.raw[0]]));
	}

	// What Mesa does: see LexerTest.
	@Test
	public void aNameInAPreprocessingNumberIsNoMacro() {
		assertEquals("x = 0x1e + A + 0x1e + 9 ;",
				new Compiled("#define A 9\nx = 0x1e+A + 0x1e +A;").text());
		assertEquals("x = 1.0f oo + 1 ;",
				new Compiled("#define oo 1\nx = 1.0foo + oo;").text());
		assertEquals("y = 1. e ;", new Compiled("#define e 2\n" +
				"#define G(e) 1.e\ny = G(3);").text());
	}

	@Test
	public void aParenthesisRightBehindTheNameMakesAMacroTakeArguments() {
		assertEquals("a = 7. ;", new Compiled("#define F\\\n(x) x\na = F(7.);")
				.text());
		assertEquals("a = ( x ) x ( 7. ) ;",
				new Compiled("#define F  (x) x\na = F(7.);").text());
		assertEquals("a = ( x ) x ( 7. ) ;",
				new Compiled("#define F/**/(x) x\na = F(7.);").text());
	}

	@Test
	public void trimmedTokensTakeMore() {
		CompiledTokens tokens = new CompiledTokens();
		tokens.trim();
		tokens.add(Words.SEMICOLON, RawTokens.PUNCTUATOR, 0, -1);
		tokens.add(Words.SEMICOLON, RawTokens.PUNCTUATOR, null, 0, 0);
		assertEquals(2, tokens.size);
	}

	@Test
	public void conditionals() {
		assertEquals("b ; d ;", new Compiled("#define A 2\n#if A > 2\na;\n" +
				"#elif A == 2 && defined(A) && !defined B\nb;\n#else\nc;\n#endif\n" +
				"#ifndef A\nx;\n#else\nd;\n#endif").text());
		assertEquals("a ; c ;", new Compiled("#if 1\na;\n#if 0\nb;\n#elif 1\nc;\n" +
				"#else\nd;\n#endif\n#else\n#if 1\ne;\n#endif\n#define X\n#endif\n" +
				"#ifdef X\nf;\n#endif").text());
		assertEquals("a ; b ;", new Compiled("#ifdef GL_ES\na;\n#endif\n" +
				"#if __VERSION__ == 100\nb;\n#endif\n" +
				"#if __VERSION__ >= 300\nc;\n#endif").text());
		assertEquals("c ;", new Compiled("#version 300 es\n" +
				"#if __VERSION__ >= 300 && defined(GL_FRAGMENT_PRECISION_HIGH)\n" +
				"c;\n#endif").text());
	}

	@Test
	public void whatIsNotCompiledIsMarkedOff() {
		Compiled c = new Compiled("#if 0\na;\n#define B\n#else\nb;\n#endif\nc;");
		assertEquals("a ; # define B", c.flagged(RawTokens.OFF));
		assertEquals("", c.flagged(RawTokens.UNDECIDED));
		// The lines of a conditional are compiled when the code around it is.
		c = new Compiled("#if 0\n#if 1\na;\n#else\n#endif\n#endif");
		assertEquals("# if 1 a ; # else # endif", c.flagged(RawTokens.OFF));
	}

	@Test
	public void aConditionThatCannotBeDecidedIsFalseAndMarked() {
		Compiled c = new Compiled("#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
				"precision highp float;\n#else\nprecision mediump float;\n#endif\nx;");
		assertEquals("precision mediump float ; x ;", c.text());
		assertTrue(c.doubts(Preprocessor.UNDECIDED_CONDITION));
		assertEquals("# ifdef GL_FRAGMENT_PRECISION_HIGH precision highp float ; " +
				"# else precision mediump float ; # endif",
				c.flagged(RawTokens.UNDECIDED));
		assertEquals("precision highp float ;", c.flagged(RawTokens.OFF));
		// A name nothing defines, an empty condition, a division by zero.
		for (String condition : new String[]{"UNKNOWN", "", "1 / 0", "1 +", "(1",
				"1.0", "defined", "__FOO__ > 1"}) {
			c = new Compiled("#if " + condition + "\na;\n#else\nb;\n#endif");
			assertEquals(condition, "b ;", c.text());
			assertTrue(condition, c.doubts(Preprocessor.UNDECIDED_CONDITION));
		}
		// A name that is not the driver's is decided: it is not defined.
		c = new Compiled("#ifdef UNKNOWN\na;\n#endif");
		assertEquals("", c.flagged(RawTokens.UNDECIDED));
	}

	@Test
	public void whatAnUndecidedConditionalDefinesIsAGuess() {
		String start = "#define A 1\n#ifdef GL_X\n#define B 1\n#undef A\n#else\n" +
				"#define C 2\n#endif\n";
		// Each of the three may or may not be defined behind it.
		for (String name : new String[]{"A", "B", "C"}) {
			Compiled c = new Compiled(start + "#ifdef " + name + "\nx;\n#endif\n" +
					"#if defined(D) || " + name + " > 0\ny;\n#endif");
			assertEquals(name, "# ifdef " + name + " x ; # endif # if defined ( D ) " +
					"|| " + name + " > 0 y ; # endif", c.flagged(RawTokens.UNDECIDED)
					.substring(c.flagged(RawTokens.UNDECIDED).indexOf("# endif") + 8));
		}
		Compiled c = new Compiled(start + "a = A + C;");
		assertEquals("a = 1 + 2 ;", c.text());
		assertTrue(c.doubts(Preprocessor.GUESSED_MACRO));
		// A definition outside settles it again.
		c = new Compiled(start + "#define B 3\n#ifdef B\nb = B;\n#endif");
		assertEquals("b = 3 ;", c.text());
		assertFalse(c.doubts(Preprocessor.GUESSED_MACRO));
	}

	@Test
	public void whatCannotBeExpandedAsWrittenIsADoubt() {
		Compiled c = new Compiled("#define F(a, b) a + b\nx = F(1, 2;\ny = 3;");
		assertEquals("x = F ( 1 , 2 ; y = 3 ;", c.text());
		assertTrue(c.doubts(Preprocessor.UNCLOSED_INVOCATION));
		c = new Compiled("#define F(a, b) a + b\nx = F(1) + F(1, 2, 3) + F;");
		assertEquals("x = 1 + + 1 + 2 + F ;", c.text());
		assertTrue(c.doubts(Preprocessor.ARGUMENT_COUNT));
		c = new Compiled("#define P(a) a##a\n#define Q() 1\nx = P(1) + Q();");
		assertEquals("x = + 1 ;", c.text());
		assertTrue(c.doubts(Preprocessor.PASTING));
		assertFalse(c.doubts(Preprocessor.ARGUMENT_COUNT));
		c = new Compiled("#else\n#endif\n#define\n#define 1\n#define G(a\n#elif 1\na;" +
				"\n#if 1\n#else\n#else\nb;");
		assertEquals("a ;", c.text());
		assertTrue(c.doubts(Preprocessor.BROKEN_DIRECTIVE));
		assertEquals(1, c.env.open);
	}

	@Test
	public void anExpansionThatWouldNotEndIsLeft() {
		StringBuilder sb = new StringBuilder("#define A0 x x x x\n");
		for (int i = 1; i < 30; ++i) {
			sb.append("#define A").append(i).append(" A").append(i - 1)
					.append(" A").append(i - 1).append(" A").append(i - 1)
					.append(" A").append(i - 1).append('\n');
		}
		Compiled c = new Compiled(sb + "A29;");
		assertTrue(c.doubts(Preprocessor.TOO_LARGE));
		assertTrue(c.tokens.size < 1 << 20);
		c = new Compiled("#define A(x) x\n" + "A(".repeat(100) + "1" +
				")".repeat(100) + ";");
		assertTrue(c.doubts(Preprocessor.TOO_LARGE));
		assertEquals("a a a b ;", new Compiled("#define a a b\n#define b a\na b;")
				.text());
	}

	@Test
	public void itStopsWhereAnItemMayEnd() {
		Compiled c = new Compiled("#define END }\n#define S ;\n" +
				"void f() { a; { b; } }\nint x[2] = int[2](1, 2);\n" +
				"struct T { int i; } t S\nvoid g() { c; END\n d");
		assertEquals(5, c.stops);
		// A parenthesis left open does not reach past its function.
		assertEquals(2, new Compiled("void f() { x = g(1, 2; }\ny = 3;\nz = (4;\nw;")
				.stops);
	}

	@Test
	public void neverThrowsAndEnds() {
		Random random = new Random(4);
		for (String text : TreeTexts.own()) {
			for (int i = 0; i < 40; ++i) {
				text = TreeTexts.damaged(text, random);
				Compiled c = new Compiled(text);
				assertTrue(c.tokens.size <= c.raw.size * 64 + (1 << 18));
			}
		}
		for (int i = 0; i < 3000; ++i) {
			new Compiled(TreeTexts.soup(random, 1 + random.nextInt(40)));
		}
	}

	// The programs the engine compiles for the examples, the bundled
	// shaders, the ShaderToy fixtures, the directive probes and 600
	// generated texts, and what Mesa's compiler did with each line
	// (recorded by tools/directives/oracle.sh): a line Mesa compiles
	// has no token that is off, and a line Mesa does not compile has
	// none that is on. What is marked undecided is not judged: there
	// the layer says itself that it guesses.
	@Test
	public void compilesTheLinesMesaCompiles() throws IOException {
		Map<String, List<String>> records = DirectiveOracle.read(
				new File(DirectiveCases.probes().getParentFile(), "mesa.txt"));
		int judged = 0;
		int skipped = 0;
		for (DirectiveCases.Case c : DirectiveCases.all()) {
			List<String> record = records.get(c.name());
			assertNotNull(c.name(), record);
			TreeSet<Integer> active = new TreeSet<>();
			TreeSet<Integer> inactive = new TreeSet<>();
			for (String line : record) {
				if (line.startsWith("active ")) {
					active = DirectiveOracle.lines(line.substring(7));
				} else if (line.startsWith("inactive ") && record.contains("clean")) {
					// Of a program with an error Mesa compiles nothing more.
					inactive = DirectiveOracle.lines(line.substring(9));
				}
			}
			Compiled compiled = new Compiled(c.text());
			assertEquals(c.name(), c.version(), compiled.env.version);
			int line = 0;
			int at = 0;
			for (int i = 0; i < compiled.raw.size; ++i) {
				for (; at < compiled.raw.start[i]; ++at) {
					line += c.text().charAt(at) == '\n' ? 1 : 0;
				}
				if (compiled.raw.is(i, RawTokens.UNDECIDED)) {
					++skipped;
					continue;
				}
				boolean off = compiled.raw.is(i, RawTokens.OFF);
				assertFalse(c.name() + ": line " + line + " is off, Mesa compiles it",
						off && active.contains(line));
				assertFalse(c.name() + ": line " + line + " is on, Mesa skips it",
						!off && inactive.contains(line));
				judged += active.contains(line) || inactive.contains(line) ? 1 : 0;
			}
		}
		// The undecided ones are mostly in the generated texts, which
		// are made to be hard: conditions with errors in them.
		assertTrue("judged " + judged + ", undecided " + skipped,
				judged > 90000 && skipped < judged);
	}
}
