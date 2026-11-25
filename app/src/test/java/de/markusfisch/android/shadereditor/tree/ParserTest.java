package de.markusfisch.android.shadereditor.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import de.markusfisch.android.shadereditor.project.DirectiveCases;

public class ParserTest {
	private static final String HEAD = "#version 300 es\nprecision highp float;\n" +
			"out vec4 color;\n";

	@Test
	public void theAppsOwnShadersHaveNoErrorOrMissingNode() {
		for (String text : TreeTexts.own()) {
			Whole whole = new Whole(text);
			assertEquals(text, 0, whole.piece.errors + whole.piece.missing);
		}
		// And in the programs the engine compiles of them, where the
		// includes are in, every name is declared.
		for (String text : programs()) {
			Whole whole = new Whole(text);
			if (whole.piece.errors + whole.piece.missing == 0) {
				++clean;
				assertFalse(text, whole.meanings().contains("undeclared"));
			}
		}
		assertTrue("clean: " + clean, clean > 80);
	}

	private int clean;

	@Test
	public void namesMeanTheNearestDeclarationBeforeThem() {
		assertEquals("x 4 -> 4\nf 5 -> 5\nx 5 -> 5\nx 6 -> 6\nx 6 -> 5\n" +
						"x 7 -> 7\nx 7 -> 6\nx 8 -> 6\nmain 10 -> 10\ncolor 10 -> 3\n" +
						"f 10 -> 5\nx 10 -> 4\n",
				new Whole(HEAD + "float x = 1.0;\nfloat f(float x) {\n" +
						"\tfloat y, x = x;\n\t{ float x = x; }\n\treturn x;\n}\n" +
						"void main() { color = vec4(f(x)); }\n").meanings()
						.replace("y 6 -> 6\n", "").replace("color 3 -> 3\n", ""));
	}

	@Test
	public void membersMeanTheFieldsOfTheStructOfTheValue() {
		Whole whole = new Whole(HEAD + "struct A { float x; };\n" +
				"struct B { A a; float x; } b, bs[2];\nB make() { return b; }\n" +
				"void main() {\n\tvec2 x;\n" +
				"\tcolor = vec4(b.a.x, bs[1].x, make().a.x, x.x);\n" +
				"\tcolor.x = (true ? b : make()).x + B(A(1.0), 2.0).a.x;\n}\n");
		String meanings = whole.meanings();
		// b.a.x, bs[1].x, make().a.x; a swizzle of a vec2 is no name.
		assertTrue(meanings, meanings.contains("b 9 -> 5\na 9 -> 5\nx 9 -> 4\n" +
				"bs 9 -> 5\nx 9 -> 5\nmake 9 -> 6\na 9 -> 5\nx 9 -> 4\nx 9 -> 8\n"));
		assertTrue(meanings, meanings.contains("make 10 -> 6\nx 10 -> 5\n" +
				"B 10 -> 5\nA 10 -> 4\na 10 -> 5\nx 10 -> 4\n"));
		assertEquals(0, whole.piece.errors + whole.piece.missing);
	}

	@Test
	public void whatIsNotDeclaredSaysSo() {
		String meanings = new Whole(HEAD + "void main() {\n\tT t;\n" +
				"\tcolor = vec4(t.x + u + g(1.0) + sin(gl_FragCoord.x));\n}\n")
				.meanings();
		assertTrue(meanings, meanings.contains("T 5 -> undeclared\nt 5 -> 5\n"));
		assertTrue(meanings, meanings.contains("t 6 -> 5\nx 6 -> unknown\n" +
				"u 6 -> undeclared\ng 6 -> undeclared\nsin 6 -> language\n" +
				"gl_FragCoord 6 -> language\n"));
	}

	@Test
	public void aNameThroughAMacroMeansWhatItMeansThere() {
		// The k of the macro's text is declared in the block that invokes it.
		Whole whole = new Whole(HEAD + "#define LOCAL float k = 2.0;\n" +
				"#define USE(v) (v + k)\nvoid main() {\n\tfloat k = 1.0;\n" +
				"\t{\n\t\tLOCAL\n\t\tcolor = vec4(USE(k));\n\t}\n" +
				"\tcolor += vec4(USE(k));\n}\n");
		assertEquals("main 6 -> 6\nk 7 -> 7\nk 4 -> 4\ncolor 10 -> 3\n" +
				"k 10 -> 4\nk 5 -> 4\ncolor 12 -> 3\nk 12 -> 7\nk 5 -> 7\n",
				whole.meanings().replace("color 3 -> 3\n", ""));
	}

	@Test
	public void bracesMissingBeforeAFunctionEndThere() {
		Whole whole = new Whole(HEAD + "float f(float a) {\n\tif (a > 0.0) {\n" +
				"\t\treturn a;\n\nfloat g(float b) { return b; }\n" +
				"void main() { color = vec4(f(1.0) + g(2.0)); }\n");
		assertEquals("FUNCTION FUNCTION FUNCTION", kinds(whole, 2));
		assertEquals(2, whole.piece.missing);
		assertTrue(whole.meanings().endsWith("f 9 -> 4\ng 9 -> 8\n"));
		// And before what only stands outside functions.
		whole = new Whole(HEAD + "float f() {\n\treturn 1.0;\nuniform float u;\n" +
				"void main() { color = vec4(u); }\n");
		assertEquals("FUNCTION DECLARATION FUNCTION", kinds(whole, 2));
		assertTrue(whole.meanings().endsWith("u 7 -> 6\n"));
	}

	@Test
	public void whatFitsNowhereIsOneError() {
		Whole whole = new Whole(HEAD + "void main() {\n\tcolor = vec4(1.0 2.0);\n" +
				"\tcolor.x = ;\n\tfloat = 3.0;\n\tcolor.y) = 1.0;\n}\n@ $\nfloat x;\n");
		// What starts no item is skipped up to where an item ends.
		assertEquals("FUNCTION ERROR", kinds(whole, 2));
		assertTrue(whole.piece.errors > 0 && whole.piece.missing > 0);
		assertTrue(whole.meanings().endsWith("x 11 -> unread\n"));
		whole = new Whole("foo bar baz;\nvoid f( { }\nstruct { float a float b; } s;\n" +
				"float g(float) { return 1.0 }\n");
		assertEquals("DECLARATION ERROR FUNCTION DECLARATION FUNCTION",
				kinds(whole, 0));
	}

	@Test
	public void anItemSaysWhenItWantsMoreTokens() {
		for (String item : new String[]{"float x = 1.0;", "void f() { g(); }",
				"struct S { float a; } s;", "precision highp float;", "uniform B { int i; };",
				"float f(float);", ";"}) {
			Whole whole = new Whole(item);
			assertFalse(item, whole.parser.wantsMore);
			Compiled compiled = new Compiled(item);
			for (int end = 1; end < compiled.tokens.size; ++end) {
				Parser parser = new Parser(compiled.tokens, 100, new Nodes(),
						new Names());
				parser.parse(end);
				assertTrue(item + " cut at " + end, parser.wantsMore);
			}
		}
		// A struct's declarators may follow its closing brace.
		Compiled compiled = new Compiled("struct S { float a; } s;");
		Parser parser = new Parser(compiled.tokens, 100, new Nodes(), new Names());
		parser.parse(compiled.tokens.size - 2);
		assertTrue(parser.wantsMore);
	}

	@Test
	public void neverThrowsAndEnds() {
		Random random = new Random(5);
		for (String text : TreeTexts.own()) {
			for (int i = 0; i < 30; ++i) {
				text = TreeTexts.damaged(text, random);
				assertCovered(new Whole(text));
			}
		}
		for (int i = 0; i < 3000; ++i) {
			assertCovered(new Whole(TreeTexts.soup(random, 1 + random.nextInt(40))));
		}
		assertCovered(new Whole("(".repeat(5000) + "{".repeat(5000) + "-".repeat(5000)));
		assertCovered(new Whole("void f(){" + "if(a)".repeat(5000) + "x;}"));
	}

	// One function's body broken on purpose, as in the study: no name
	// outside that function may get another meaning, and few may lose
	// theirs.
	@Test
	public void aBrokenBodyChangesNoMeaningOutsideIt() {
		int[] counts = breakItems(false);
		assertTrue("broken " + counts[0], counts[0] > 500);
		assertEquals(0, counts[2]);
		assertTrue("lost in " + counts[1] + " of " + counts[0],
				counts[1] * 50 < counts[0]);
	}

	// One token broken anywhere, heads of functions and declarations
	// included. A broken declaration may declare something else, as it
	// does for a compiler, so here a name outside the broken item can
	// get another meaning; the study agreed to stop above 0.5 % of
	// broken texts.
	@Test
	public void aBrokenItemRarelyChangesAMeaningOutsideIt() {
		int[] counts = breakItems(true);
		assertTrue("broken " + counts[0], counts[0] > 500);
		assertTrue("wrong in " + counts[2] + ", lost in " + counts[1] + " of " +
				counts[0], counts[2] * 200 <= counts[0] && counts[1] * 20 < counts[0]);
	}

	// Breaks items of the programs; gives how many texts were broken,
	// in how many a name outside the broken item lost its meaning, and
	// in how many one got another.
	private static int[] breakItems(boolean anywhere) {
		Random random = new Random(7);
		int[] counts = new int[3];
		for (String text : programs()) {
			Whole whole = new Whole(text);
			List<int[]> items = items(whole, anywhere);
			if (whole.piece.errors + whole.piece.missing > 0 || items.isEmpty()) {
				continue;
			}
			for (int k = 0; k < 12; ++k) {
				int[] item = items.get(random.nextInt(items.size()));
				int[] cut = new int[1];
				String mutant = broken(whole, item, random, anywhere ? 3 : 5, cut);
				if (mutant == null) {
					continue;
				}
				int delta = mutant.length() - text.length();
				// Names that mean what the item itself declares are
				// about the damage, not outside it.
				Map<String, String> before = outside(whole, item[0], item[1],
						Integer.MAX_VALUE, 0);
				Whole after = new Whole(mutant);
				boolean lost = false;
				boolean wrong = false;
				for (Map.Entry<String, String> meaning : outside(after, item[0],
						item[1] + delta, cut[0], delta).entrySet()) {
					String was = before.get(meaning.getKey());
					if (was == null || was.equals("in")) {
						continue;
					}
					lost |= meaning.getValue().equals("?");
					wrong |= !meaning.getValue().equals("?") &&
							!meaning.getValue().equals(was);
				}
				++counts[0];
				counts[1] += lost ? 1 : 0;
				counts[2] += wrong ? 1 : 0;
			}
		}
		return counts;
	}

	// The kinds of the items behind the first skip of them.
	private static String kinds(Whole whole, int skip) {
		String[] names = {"", "FUNCTION", "PROTOTYPE", "DECLARATION", "PRECISION",
				"STRUCT"};
		StringBuilder sb = new StringBuilder();
		for (int i = skip * 3; i < whole.piece.items.length; i += 3) {
			int kind = whole.piece.items[i];
			sb.append(sb.length() > 0 ? " " : "").append(kind == Nodes.ERROR
					? "ERROR" : names[kind]);
		}
		return sb.toString();
	}

	// Every compiled token is in exactly the items, in order.
	private static void assertCovered(Whole whole) {
		int at = 0;
		for (int i = 0; i < whole.piece.items.length; i += 3) {
			assertEquals(whole.text, at, whole.piece.items[i + 1]);
			at = whole.piece.items[i + 2];
			assertTrue(whole.text, at > whole.piece.items[i + 1]);
		}
		assertEquals(whole.text, whole.piece.tokens.size, at);
		for (int i = 0; i < whole.piece.nodes.size; ++i) {
			assertTrue(whole.text, whole.piece.nodes.first[i] <=
					whole.piece.nodes.end[i]);
		}
	}

	// The programs the engine compiles for the examples and the bundled
	// shaders, and the review probes.
	static List<String> programs() {
		List<String> texts = new ArrayList<>();
		for (DirectiveCases.Case c : DirectiveCases.all()) {
			if (!c.name().startsWith("generated/")) {
				texts.add(c.text());
			}
		}
		return texts;
	}

	// The items that no macro has a part in: where each starts and ends
	// in the text, and the tokens that may be broken; of functions only
	// the bodies, unless every token of any item may be.
	private static List<int[]> items(Whole whole, boolean anywhere) {
		List<int[]> items = new ArrayList<>();
		Piece piece = whole.piece;
		for (int i = 0; i < piece.items.length; i += 3) {
			int first = piece.items[i + 1];
			int end = piece.items[i + 2];
			boolean plain = anywhere || piece.items[i] == Nodes.FUNCTION;
			int open = -1;
			for (int t = first; t < end && plain; ++t) {
				plain = piece.tokens.raw[t] >= 0 && piece.tokens.via[t] < 0;
				if (open < 0 && piece.tokens.symbol[t] == Words.LEFT_BRACE) {
					open = t;
				}
			}
			open = anywhere ? first - 1 : open;
			if (plain && (anywhere || open >= 0) && end - open > 3) {
				items.add(new int[]{whole.at(first),
						piece.raw.end[piece.tokens.raw[end - 1]], open, end});
			}
		}
		return items;
	}

	// The text with a token of the item broken in one of the first
	// ways of: deleted, doubled, replaced by punctuation, everything up
	// to the last token cut off, the last token deleted. Null if that
	// changes nothing. cut gets where the text changes.
	private static String broken(Whole whole, int[] item, Random random,
			int ways, int[] cut) {
		Piece piece = whole.piece;
		int token = item[2] + 1 + random.nextInt(item[3] - item[2] - 2);
		int from = whole.at(token);
		int to = piece.raw.end[piece.tokens.raw[token]];
		int last = whole.at(item[3] - 1);
		String with = "";
		switch (random.nextInt(ways)) {
			case 0:
				break;
			case 1:
				with = whole.text.substring(from, to) + " " +
						whole.text.substring(from, to);
				break;
			case 2:
				with = new String[]{"(", ")", "{", "}", ";", ",", "+"}[
						random.nextInt(7)];
				break;
			case 3:
				to = last;
				break;
			default:
				from = last;
				to = last + 1;
				break;
		}
		cut[0] = from;
		return to <= from && with.isEmpty() ? null
				: whole.text.substring(0, from) + with + whole.text.substring(to);
	}

	// For every name that stands outside [start, end): its place, and
	// the place of what it means, "?" if it is not declared or not
	// read, "in" if what it means is declared inside. Places behind
	// from are moved back by delta.
	private static Map<String, String> outside(Whole whole, int start, int end,
			int from, int delta) {
		Map<String, String> meanings = new HashMap<>();
		for (int i = 0; i < whole.means.length; ++i) {
			Meanings.Declared d = whole.means[i];
			int place = whole.place(i);
			if (d == null || d == Meanings.LANGUAGE ||
					(place >= start && place < end)) {
				continue;
			}
			String means = d.piece == null && d.kind == 0 ? "?" : d.piece == null
					? "-" : whole.at(d.token) >= start && whole.at(d.token) < end
					? "in" : String.valueOf(shift(whole.at(d.token), from, delta));
			String at = shift(whole.at(i), from, delta) + "/" +
					shift(place, from, delta);
			String other = meanings.put(at, means);
			if (other != null && !other.equals(means)) {
				// One writing in a macro's text with two meanings.
				meanings.put(at, other.compareTo(means) < 0 ? other + means
						: means + other);
			}
		}
		return meanings;
	}

	private static int shift(int at, int from, int delta) {
		return at < from ? at : at - delta;
	}
}
