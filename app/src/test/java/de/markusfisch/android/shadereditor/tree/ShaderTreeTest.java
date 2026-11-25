package de.markusfisch.android.shadereditor.tree;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class ShaderTreeTest {
	private static final String PROGRAM = "#version 300 es\n" +
			"precision highp float;\n#define SCALE 2.0\nout vec4 color;\n" +
			"uniform float time;\n\nfloat wave(float x) {\n\treturn sin(x * SCALE);\n}\n\n" +
			"struct Light { vec3 at; float power; } light;\n\n" +
			"void main() {\n\tfloat w = wave(time);\n\tcolor = vec4(w);\n}\n";

	@Test
	public void thePiecesAreTheItemsAndCoverTheText() {
		ShaderTree tree = ShaderTree.read(PROGRAM);
		assertEquals(7, tree.pieces.length);
		assertEquals(PROGRAM.length(), tree.starts[tree.pieces.length]);
		// The struct's declarator stands behind its closing brace.
		assertEquals("struct Light { vec3 at; float power; } light;",
				PROGRAM.substring(tree.starts[4], tree.starts[5]).trim());
		assertEquals(0, ShaderTree.read("").pieces[0].length);
		assertEquals(3, ShaderTree.read(" \n ").pieces[0].length);
	}

	// A text read piece by piece is the text read whole: every token,
	// node, step, definition and doubt, counted from the start of the
	// text. So where the pieces end changes nothing.
	@Test
	public void piecesReadAsTheWholeText() {
		Random random = new Random(13);
		int pieces = 0;
		for (String text : ParserTest.programs()) {
			pieces += assertReadsAsWhole(text);
			for (int i = 0; i < 6; ++i) {
				text = TreeTexts.damaged(text, random);
				assertReadsAsWhole(text);
			}
		}
		for (int i = 0; i < 2000; ++i) {
			assertReadsAsWhole(TreeTexts.soup(random, 1 + random.nextInt(40)));
		}
		assertTrue(pieces > 800);
	}

	// Where a piece may not end although items are whole before it.
	@Test
	public void aPieceEndsOnlyWhereTheRestReadsAsIfNothingWasRead() {
		// In each, a brace is not closed and a long function follows, so
		// that tokens are parsed in the middle of it. Whole items stand
		// before it, but: a directive behind them has been run already.
		String big = "float big() { " + "a; ".repeat(700) + "}\nfloat c = B;\n";
		assertReadsAsWhole("void g() {\nuniform float a;\n#define B 2\n" + big);
		// The last of them ends inside the arguments of an invocation.
		assertReadsAsWhole("#define ID(x) x\nvoid g() {\nuniform float u;\n" +
				"ID(float b;) " + big);
		// Behind it stands a name the preprocessor takes into a number.
		assertReadsAsWhole("void g() { x = 1.float f() { " + "a; ".repeat(600) +
				"}\nfloat c;\n");
		// A brace is not closed: the function ends before the next one,
		// and so does its piece.
		String open = "void g() {\n\tx = 1.0;\n\n" + ("float f(float a) {\n" +
				"\treturn a;\n}\n").repeat(400);
		assertTrue(assertReadsAsWhole(open) > 300);
	}

	// Gives how many pieces the text is read in.
	private static int assertReadsAsWhole(String text) {
		ShaderTree tree = ShaderTree.read(text);
		String whole = normal(new Whole(text).piece);
		String read = normal(tree.pieces);
		if (!whole.equals(read)) {
			int at = 0;
			while (at < whole.length() && at < read.length() &&
					whole.charAt(at) == read.charAt(at)) {
				++at;
			}
			throw new AssertionError("DIFF\n" + whole.substring(Math.max(0, at - 150),
					Math.min(whole.length(), at + 100)) + "\n---\n" +
					read.substring(Math.max(0, at - 150),
							Math.min(read.length(), at + 100)) + "\n---\n" + text);
		}
		return tree.pieces.length;
	}

	// Everything pieces hold, with offsets and indices counted from
	// the start of the first.
	private static String normal(Piece... pieces) {
		StringBuilder raw = new StringBuilder("raw");
		StringBuilder tokens = new StringBuilder("\ntokens");
		StringBuilder nodes = new StringBuilder("\nnodes");
		StringBuilder names = new StringBuilder("\nnames");
		StringBuilder items = new StringBuilder("\nitems");
		StringBuilder doubts = new StringBuilder("\ndoubts");
		StringBuilder macros = new StringBuilder("\nmacros");
		int at = 0;
		int raws = 0;
		int compiled = 0;
		int steps = 0;
		int errors = 0;
		for (Piece p : pieces) {
			Symbols s = p.symbols;
			for (int i = 0; i < p.raw.size; ++i) {
				raw.append(' ').append(p.raw.kind[i]).append(':')
						.append(p.raw.start[i] + at).append(':')
						.append(p.raw.end[i] + at).append(':').append(p.raw.flags[i])
						.append(':').append(s.name(p.raw.symbol[i]));
			}
			for (int i = 0; i < p.tokens.size; ++i) {
				int r = p.tokens.raw[i];
				tokens.append(' ').append(p.tokens.kind[i]).append(':')
						.append(p.spelling(i)).append(':').append(r >= 0
								? String.valueOf(r + raws)
								: s.name(p.tokens.macro[-1 - r].name) + "." +
										p.tokens.bodyIndex[-1 - r]).append(':')
						.append(p.tokens.via[i] < 0 ? -1 : p.tokens.via[i] + raws);
			}
			for (int i = 0; i < p.nodes.size; ++i) {
				nodes.append(' ').append(p.nodes.kind[i]).append(':')
						.append(p.nodes.first[i] + compiled).append(':')
						.append(p.nodes.end[i] + compiled);
			}
			for (int i = 0; i < p.names.size; ++i) {
				byte step = p.names.step[i];
				int a = p.names.a[i];
				int b = p.names.b[i];
				boolean steps2 = step == Names.OPEN || step == Names.EITHER;
				names.append(' ').append(step).append(':')
						.append(step == Names.CLOSE || step == Names.END || a < 0
								? a : a + (steps2 ? steps : compiled)).append(':')
						.append(step == Names.END || step == Names.OPEN ||
								step == Names.CLOSE || b < 0 ||
								(b == 0 && step != Names.VARIABLE && step != Names.FUNCTION
										&& step != Names.FIELD && step != Names.MEMBER
										&& step != Names.EITHER) ? b : b + steps);
			}
			for (int i = 0; i < p.items.length; i += 3) {
				items.append(' ').append(p.items[i]).append(':')
						.append(p.items[i + 1] + compiled).append(':')
						.append(p.items[i + 2] + compiled);
			}
			for (int i = 0; i < p.doubts.length; i += 2) {
				doubts.append(' ').append(p.doubts[i] + raws).append(':')
						.append(p.doubts[i + 1]);
			}
			for (Macro m : p.definitions) {
				macros.append(' ').append(s.name(m.name)).append(m.function)
						.append(m.guess).append(m.nameAt + raws).append(':')
						.append(m.bodyAt < 0 ? -1 : m.bodyAt + raws).append(':')
						.append(m.isDefined() ? m.body.length : 0);
			}
			errors += p.errors * 100000 + p.missing;
			at += p.length;
			raws += p.raw.size;
			compiled += p.tokens.size;
			steps += p.names.size;
		}
		return raw.append(tokens).append(nodes).append(names).append(items)
				.append(doubts).append(macros).append('\n').append(errors)
				.append(' ').append(at).toString();
	}

	@Test
	public void aKeyInAFunctionReadsOnlyThatFunctionAgain() {
		ShaderTree tree = ShaderTree.read(PROGRAM);
		int at = PROGRAM.indexOf("x * SCALE");
		String typed = PROGRAM.substring(0, at) + "y" + PROGRAM.substring(at);
		ShaderTree after = tree.edit(typed, at, at, at + 1);
		assertEquals(tree.pieces.length - 1, after.reused);
		assertEquals(describe(ShaderTree.read(typed)), describe(after));
		// A key in a #define line reads again what stands behind it.
		at = PROGRAM.indexOf("2.0");
		typed = PROGRAM.substring(0, at) + "1" + PROGRAM.substring(at);
		after = tree.edit(typed, at, at, at + 1);
		assertEquals(1, after.reused);
		assertEquals(describe(ShaderTree.read(typed)), describe(after));
		// Unless it leaves the macro as it was.
		at = PROGRAM.indexOf("SCALE 2.0") + 5;
		typed = PROGRAM.substring(0, at) + "  " + PROGRAM.substring(at);
		after = tree.edit(typed, at, at, at + 2);
		assertEquals(tree.pieces.length - 1, after.reused);
		assertEquals(describe(ShaderTree.read(typed)), describe(after));
	}

	@Test
	public void aPieceFromTheStartOfTheTextIsReadAgainElsewhere() {
		// Behind the ";" the "#" is not first on its line any more.
		String text = "#define A 1\nfloat a = A;\n";
		ShaderTree after = ShaderTree.read(text).edit(";" + text, 0, 0, 1);
		assertEquals(describe(ShaderTree.read(";" + text)), describe(after));
		// And the other way round.
		after = ShaderTree.read(";" + text).edit(text, 0, 1, 0);
		assertEquals(describe(ShaderTree.read(text)), describe(after));
	}

	@Test
	public void aNameThatBecomesAGuessIsReadAgainWhereItIsAskedFor() {
		String text = "#ifdef GL_X\n\n#endif\nfloat a;\n#ifdef A\nfloat b;\n" +
				"#endif\nfloat c;\n";
		ShaderTree tree = ShaderTree.read(text);
		int at = text.indexOf("\n\n") + 1;
		String edited = text.substring(0, at) + "#undef A" + text.substring(at);
		ShaderTree after = tree.edit(edited, at, at, at + 8);
		assertEquals(describe(ShaderTree.read(edited)), describe(after));
		assertEquals(0, after.reused);
	}

	@Test
	public void aTreeFindsWhatChangedItself() {
		ShaderTree tree = ShaderTree.read(PROGRAM);
		for (String edited : new String[]{PROGRAM, "", PROGRAM + PROGRAM,
				PROGRAM.replace("wave", "w"), PROGRAM.substring(40), "x" + PROGRAM}) {
			assertEquals(describe(ShaderTree.read(edited)),
					describe(tree.edit(edited)));
		}
		// All but the last piece, which was read up to the end of the text.
		assertEquals(tree.pieces.length - 1, tree.edit(PROGRAM).reused);
	}

	@Test
	public void whatANameMeansByWhereItIsWritten() {
		String text = "#define K 3\n#define USE (a + K)\n#ifdef K\nfloat a;\n" +
				"#endif\n#undef K\nfloat f(float a) { return USE; }\n" +
				"float g() { return USE + sin(K) + b; }\n#if 0\nfloat c;\n#endif\n";
		ShaderTree tree = ShaderTree.read(text);
		// The a of the macro's text: the parameter in f, the global in g.
		assertEquals("DECLARED " + text.indexOf("a) {") + ", DECLARED " +
				text.indexOf("a;"), meanings(tree, text.indexOf("a + K")));
		// K where it is defined, asked for and undefined.
		String k = "MACRO " + text.indexOf("K 3");
		assertEquals(k, meanings(tree, text.indexOf("K 3")));
		assertEquals(k, meanings(tree, text.indexOf("K\nfloat")));
		assertEquals(k, meanings(tree, text.indexOf("K\nfloat f")));
		// Behind its #undef it is a name like any other, also the K of
		// the macro's text where the macro is invoked.
		assertEquals("NOT_DECLARED", meanings(tree, text.indexOf("K) + b")));
		assertEquals("NOT_DECLARED, NOT_DECLARED",
				meanings(tree, text.indexOf("K)")));
		assertEquals("MACRO " + text.indexOf("USE ("),
				meanings(tree, text.indexOf("USE;")));
		assertEquals("LANGUAGE", meanings(tree, text.indexOf("sin")));
		assertEquals("NOT_DECLARED", meanings(tree, text.indexOf("b;")));
		assertEquals("NOT_COMPILED", meanings(tree, text.indexOf("c;")));
		// Nothing a compiler sees: a macro's name in a branch that is
		// off, and an argument its macro leaves out.
		assertEquals("NOT_COMPILED", meanings(ShaderTree.read(
				"#define K 1\n#if 0\nfloat a = K;\n#endif\n"), 28));
		assertEquals("NOT_COMPILED", meanings(ShaderTree.read(
				"#define DROP(x) 1.0\nfloat y;\nfloat a = DROP(y);\n"), 44));
		assertEquals("", meanings(tree, text.indexOf("+ K")));
		assertEquals("DECLARED " + text.indexOf("f("),
				meanings(tree, text.indexOf("f(") + 1));
		assertTrue(!tree.isBroken() && ShaderTree.read("float a = ;").isBroken());
	}

	private static String meanings(ShaderTree tree, int offset) {
		StringBuilder sb = new StringBuilder();
		for (ShaderTree.Meaning meaning : tree.meanings(offset)) {
			sb.append(sb.length() > 0 ? ", " : "").append(meaning.kind);
			if (meaning.end > 0) {
				sb.append(' ').append(meaning.start);
			}
		}
		return sb.toString();
	}

	// The test of re-reading: after any edits the tree is the tree a
	// reading from nothing gives, in every token, node and step.
	@Test
	public void theTreeAfterEditsIsTheTreeReadFromNothing() {
		Random random = new Random(11);
		int reused = 0;
		int pieces = 0;
		List<String> programs = ParserTest.programs();
		for (int round = 0; round < 150; ++round) {
			String text = programs.get(random.nextInt(programs.size()));
			ShaderTree tree = ShaderTree.read(text);
			for (int k = 0; k < 40; ++k) {
				int start = random.nextInt(text.length() + 1);
				int oldEnd = Math.min(text.length(), start + (random.nextInt(4) == 0
						? random.nextInt(40) : random.nextInt(2)));
				String with = random.nextInt(3) == 0 ? ""
						: random.nextInt(8) == 0
						? text.substring(start, Math.min(text.length(),
								start + random.nextInt(300)))
						: TreeTexts.soup(random, 1);
				String edited = text.substring(0, start) + with +
						text.substring(oldEnd);
				tree = tree.edit(edited, start, oldEnd, start + with.length());
				ShaderTree fresh = ShaderTree.read(edited);
				assertEquals(text + "\n----\n" + edited, describe(fresh),
						describe(tree));
				reused += tree.reused;
				pieces += tree.pieces.length;
				text = edited;
			}
		}
		// And it is not that nothing is ever taken over.
		assertTrue(reused + " of " + pieces, reused * 3 > pieces);
	}

	@Test
	public void aTableOfSymbolsDoesNotOutgrowTheText() {
		String head = "float ";
		StringBuilder name = new StringBuilder("a");
		ShaderTree tree = ShaderTree.read(head + name + ";");
		for (int i = 0; i < 3000; ++i) {
			name.append((char) ('a' + i % 26));
			int at = head.length() + name.length() - 1;
			tree = tree.edit(head + name + ";", at, at, at + 1);
		}
		assertTrue(tree.symbols.characters() < 4 * name.length() + (1 << 17));
		assertEquals(describe(ShaderTree.read(head + name + ";")), describe(tree));
	}

	// Everything a tree holds, with symbols as they are spelled.
	static String describe(ShaderTree tree) {
		StringBuilder sb = new StringBuilder();
		Symbols s = tree.symbols;
		for (Piece p : tree.pieces) {
			sb.append("piece ").append(p.length).append(' ').append(p.reach)
					.append(' ').append(p.first).append(' ').append(p.versionBefore)
					.append(Arrays.toString(p.conditionalsBefore)).append(p.versionAfter)
					.append(Arrays.toString(p.conditionalsAfter)).append(p.errors)
					.append(' ').append(p.missing).append('\n');
			for (int i = 0; i < p.raw.size; ++i) {
				sb.append(p.raw.kind[i]).append(':').append(p.raw.start[i]).append(':')
						.append(p.raw.end[i]).append(':').append(p.raw.flags[i])
						.append(':').append(s.name(p.raw.symbol[i])).append(' ');
			}
			sb.append('\n');
			for (int i = 0; i < p.tokens.size; ++i) {
				int raw = p.tokens.raw[i];
				sb.append(p.tokens.kind[i]).append(':').append(p.spelling(i))
						.append(':').append(raw >= 0 ? String.valueOf(raw)
								: s.name(p.tokens.macro[-1 - raw].name) + "." +
										p.tokens.bodyIndex[-1 - raw])
						.append(':').append(p.tokens.via[i]).append(' ');
			}
			sb.append('\n');
			for (int i = 0; i < p.nodes.size; ++i) {
				sb.append(p.nodes.kind[i]).append(':').append(p.nodes.first[i])
						.append(':').append(p.nodes.end[i]).append(' ');
			}
			sb.append('\n');
			for (int i = 0; i < p.names.size; ++i) {
				sb.append(p.names.step[i]).append(':').append(p.names.a[i])
						.append(':').append(p.names.b[i]).append(' ');
			}
			sb.append('\n').append(Arrays.toString(p.items))
					.append(Arrays.toString(p.doubts));
			for (Macro m : p.definitions) {
				sb.append(' ').append(s.name(m.name)).append(m.function)
						.append(m.guess).append(m.bodyAt).append('(');
				for (int i = 0; m.isDefined() && i < m.parameters.length; ++i) {
					sb.append(s.name(m.parameters[i])).append(',');
				}
				for (int i = 0; m.isDefined() && i < m.body.length; ++i) {
					sb.append(' ').append(s.name(m.body[i])).append(m.kinds[i]);
				}
			}
			sb.append('\n');
		}
		return sb.append(tree.openConditionals).toString();
	}
}
