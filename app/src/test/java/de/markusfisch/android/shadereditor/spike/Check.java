package de.markusfisch.android.shadereditor.spike;

import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.spike.Parser.Node;

/**
 * SPIKE, throwaway: runs options A and B over the bundled shaders and
 * prints what they find, to see that the prototypes read real code.
 */
final class Check {
	private Check() {
	}

	public static void main(String[] args) {
		boolean verbose = args.length > 0;
		int files = 0;
		int problems = 0;
		int expressions = 0;
		int typed = 0;
		int names = 0;
		int unresolved = 0;
		for (Map.Entry<String, String> e : Corpus.bundled().entrySet()) {
			TokenStream t = TokenStream.of(Text.of(e.getValue()));
			Parser parser = new Parser(t);
			Node unit = parser.unit();
			Sema sema = Sema.analyse(t, unit);
			Shallow.Outline outline = Shallow.scan(t);
			++files;
			problems += parser.problemCount;
			expressions += sema.expressions;
			typed += sema.typed;
			names += sema.names;
			unresolved += sema.unresolved;
			if (verbose || parser.problemCount > 0) {
				System.out.println(e.getKey() + ": " + t.size + " tokens, " +
						parser.nodes + " nodes, " + parser.problemCount +
						" problems, typed " + sema.typed + "/" +
						sema.expressions + ", unresolved " + sema.unresolved +
						"/" + sema.names + ", shallow " +
						outline.declarations.size() + " declarations, " +
						outline.localCount + " locals");
				for (Parser.Problem p : parser.problems) {
					int line = t.lineOf(p.offset());
					System.out.println("    line " + (line + 1) + ": " +
							p.message() + " at '" + e.getValue().substring(
							p.offset(), Math.min(e.getValue().length(),
									p.offset() + 20)).replace("\n", "\\n") +
							"'");
				}
			}
		}
		for (String base : new String[]{".", "app"}) {
			java.io.File hostile = new java.io.File(base,
					"src/test/resources/spike/hostile.frag");
			if (hostile.isFile()) {
				one("hostile.frag", read(hostile));
			}
		}
		System.out.println(files + " files, " + problems + " problems, " +
				"typed " + typed + "/" + expressions + ", unresolved names " +
				unresolved + "/" + names);
		String g = Corpus.generated(1000, 42);
		TokenStream t = TokenStream.of(Text.of(g));
		Parser parser = new Parser(t);
		Sema sema = Sema.analyse(t, parser.unit());
		System.out.println("generated 1000: " + parser.problemCount +
				" problems, typed " + sema.typed + "/" + sema.expressions +
				", unresolved " + sema.unresolved + "/" + sema.names);
		for (Parser.Problem p : parser.problems) {
			System.out.println("    line " + (t.lineOf(p.offset()) + 1) +
					": " + p.message());
		}
		untyped(t, sema.unit, 0, new int[1]);
	}

	private static String read(java.io.File file) {
		try {
			return new String(java.nio.file.Files.readAllBytes(
					file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
		} catch (java.io.IOException e) {
			throw new AssertionError(e);
		}
	}

	// Prints what B makes of one text, with every problem.
	static void one(String name, String source) {
		TokenStream t = TokenStream.of(Text.of(source));
		Parser parser = new Parser(t);
		Node unit = parser.unit();
		Sema sema = Sema.analyse(t, unit);
		Shallow.Outline outline = Shallow.scan(t);
		System.out.println(name + ": " + parser.problemCount +
				" problems, typed " + sema.typed + "/" + sema.expressions +
				", unresolved " + sema.unresolved + "/" + sema.names +
				"; B knows " + sema.globals.size() + " globals, A " +
				outline.declarations.size() + " declarations and " +
				outline.localCount + " locals");
		for (Parser.Problem p : parser.problems) {
			System.out.println("    line " + (t.lineOf(p.offset()) + 1) +
					": " + p.message() + " at '" + source.substring(
					p.offset(), Math.min(source.length(), p.offset() + 24))
					.replace("\n", "\\n") + "'");
		}
		untyped(t, unit, 0, new int[1]);
	}

	// Prints the first expressions without a type.
	private static void untyped(TokenStream t, Node node, int depth,
			int[] count) {
		if (node == null || count[0] > 12) {
			return;
		}
		if (node.kind >= Parser.NAME && node.type != null &&
				node.type.kind == Types.UNKNOWN) {
			boolean leaf = true;
			for (Node c : new Node[]{node.a, node.b, node.c}) {
				leaf &= c == null || c.type == null ||
						c.type.kind != Types.UNKNOWN;
			}
			for (Node c : node.list) {
				leaf &= c.type == null || c.type.kind != Types.UNKNOWN;
			}
			if (leaf) {
				++count[0];
				System.out.println("    untyped at line " +
						(t.lineOf(node.start) + 1) + ": " +
						t.source.substring(node.start, node.end));
			}
		}
		untyped(t, node.a, depth + 1, count);
		untyped(t, node.b, depth + 1, count);
		untyped(t, node.c, depth + 1, count);
		for (Node c : node.list) {
			untyped(t, c, depth + 1, count);
		}
	}
}
