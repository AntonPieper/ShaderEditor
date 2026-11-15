package de.markusfisch.android.shadereditor.spike;

import java.util.Locale;

import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.spike.Parser.Node;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * SPIKE, throwaway: the cost of options A, B and C at 1,000, 5,000 and
 * 50,000 lines. Question 2 of the study.
 */
final class Report {
	private Report() {
	}

	public static void main(String[] args) {
		Locale.setDefault(Locale.ROOT);
		System.out.println("# Options A, B, C: cost");
		System.out.println(Bench.machine());
		int[] sizes = {1000, 5000, 50000};
		for (String corpus : new String[]{"generated", "repeated"}) {
			for (int size : sizes) {
				String source = corpus.equals("repeated")
						? Corpus.repeated(size)
						: Corpus.generated(size, 42);
				run(corpus, source);
			}
		}
	}

	static void run(String corpus, String source) {
		Syntax syntax = GlslSyntax.INSTANCE;
		Text text = Text.of(source);
		int lines = text.lineCount();
		int runs = lines > 20000 ? 30 : 60;
		System.out.println();
		System.out.println("## " + corpus + ", " + lines + " lines, " +
				source.length() + " chars");

		// --- A, part 1: the shape of every line.
		Bench.Stats lexOnly = Bench.time(() -> {
			LineTokens tokens = new LineTokens();
			int state = Syntax.TEXT_START;
			for (int n = 0; n < lines; ++n) {
				state = syntax.lexLine(text.line(n), n < lines - 1, state,
						tokens);
			}
			return state;
		}, 1000, runs);
		short[] shapes = new short[lines];
		Bench.Stats lexShape = Bench.time(() -> {
			LineTokens tokens = new LineTokens();
			int state = Syntax.TEXT_START;
			for (int n = 0; n < lines; ++n) {
				String line = text.line(n);
				boolean inComment = (state & 1) != 0;
				state = syntax.lexLine(line, n < lines - 1, state, tokens);
				shapes[n] = Shallow.shape(line, tokens, inComment);
			}
			return shapes;
		}, 1000, runs);
		Bench.Stats folds = Bench.time(() -> Shallow.folds(shapes, lines),
				500, runs);
		int[] ranges = Shallow.folds(shapes, lines);
		// The longest walk: from the first line that opens a block.
		int opener = 0;
		while (opener < lines && Shallow.blockEnd(shapes, lines,
				opener) < 0) {
			++opener;
		}
		Bench.Stats walk = Bench.time(() -> {
			// A walk over every line: the worst a block end can cost.
			int depth = 0;
			for (int n = 0; n < lines; ++n) {
				int shape = shapes[n] & 0xffff;
				depth += ((shape & Shallow.OPEN_MASK) >>
						Shallow.OPEN_SHIFT) - (shape & Shallow.CLOSE_MASK);
			}
			return depth;
		}, 300, runs);
		System.out.println("A1 line states only (today): " + lexOnly.ms());
		System.out.println("A1 line states + shapes: " + lexShape.ms() +
				"; per line " + Bench.fmtUs(lexShape.medianMs() / lines) +
				" against " + Bench.fmtUs(lexOnly.medianMs() / lines) +
				"; kept: " + lines * 2 / 1024 + " KB (2 bytes a line)");
		System.out.println("A1 folding ranges from the shapes: " +
				folds.us() + ", " + ranges.length / 2 + " ranges, allocates " +
				folds.allocBytes() / 1024 + " KB");
		System.out.println("A1 walk over all shapes (worst matching " +
				"brace): " + walk.us());

		// --- The tokens both scans read.
		Bench.Stats stream = Bench.time(() -> TokenStream.of(text), 1500,
				runs);
		TokenStream t = TokenStream.of(text);
		long streamKept = Bench.retained(() -> TokenStream.of(text), 4);
		System.out.println("tokens with names as numbers: " + stream.ms() +
				", allocates " + stream.mb() + ", keeps " +
				streamKept / 1024 + " KB (" + t.size + " tokens, " +
				t.names.size() + " names, text copy included)");

		// --- A, part 2: the shallow scan.
		Bench.Stats scan = Bench.time(() -> Shallow.scan(t), 1500, runs);
		Shallow.Outline outline = Shallow.scan(t);
		long scanKept = Bench.retained(() -> {
			Shallow.Outline o = Shallow.scan(t);
			// Without the tokens: what an outline that copied its
			// strings would keep.
			return new Object[]{o.declarations, o.locals};
		}, 4);
		System.out.println("A2 shallow scan of the tokens: " + scan.ms() +
				", allocates " + scan.mb() + ", keeps " + scanKept / 1024 +
				" KB without the tokens (" + outline.declarations.size() +
				" declarations, " + outline.localCount + " locals)");
		System.out.println("A  whole file (tokens + scan): " +
				Bench.fmt(stream.medianMs() + scan.medianMs()));

		// --- B: parse, then names and types.
		Bench.Stats parse = Bench.time(() -> Parser.parse(t), 2000, runs);
		Parser parser = new Parser(t);
		Node unit = parser.unit();
		Bench.Stats sema = Bench.time(() -> Sema.analyse(t, unit), 2000,
				runs);
		Sema s = Sema.analyse(t, unit);
		Bench.Stats whole = Bench.time(() -> {
			TokenStream ts = TokenStream.of(text);
			return Sema.analyse(ts, Parser.parse(ts));
		}, 2000, runs);
		long treeKept = Bench.retained(() -> Sema.analyse(t,
				Parser.parse(t)), 4);
		System.out.println("B parse: " + parse.ms() + ", allocates " +
				parse.mb() + " (" + parser.nodes + " nodes, " +
				parser.problemCount + " problems)");
		System.out.println("B names and types: " + sema.ms() +
				", allocates " + sema.mb() + " (typed " + s.typed + "/" +
				s.expressions + ", unresolved names " + s.unresolved + "/" +
				s.names + ")");
		System.out.println("B whole file (tokens + parse + types): " +
				whole.ms() + ", allocates " + whole.mb() + ", keeps " +
				(treeKept + streamKept) / 1024 + " KB (tree " +
				treeKept / 1024 + " KB + tokens " + streamKept / 1024 +
				" KB)");

		// What B keeps if it drops the bodies after the walk: the
		// globals with their signatures, to parse a body again on demand.
		long leanKept = Bench.retained(() -> {
			Node u = Parser.parse(t);
			Sema lean = Sema.analyse(t, u);
			int[] ranges2 = new int[u.list.length * 2];
			int i = 0;
			for (Node n : u.list) {
				ranges2[i++] = n.start;
				ranges2[i++] = n.end;
				if (n.kind == Parser.FUNCTION) {
					n.b = null;
				}
			}
			return new Object[]{lean.globals, lean.visible, ranges2};
		}, 4);
		System.out.println("B lean (globals and ranges only, bodies " +
				"parsed again on demand): keeps " + leanKept / 1024 +
				" KB");

		// --- C: only the function that changed.
		Node[] functions = java.util.Arrays.stream(unit.list)
				.filter(n -> n.kind == Parser.FUNCTION && n.b != null)
				.toArray(Node[]::new);
		if (functions.length > 2) {
			Node[] bySize = functions.clone();
			java.util.Arrays.sort(bySize, (x, y) -> Integer.compare(
					x.end - x.start, y.end - y.start));
			Node median = bySize[bySize.length / 2];
			Node largest = bySize[bySize.length - 1];
			for (Node f : new Node[]{median, largest}) {
				int from = t.tokenAt(f.start);
				int to = t.tokenAt(f.end);
				Bench.Stats one = Bench.timeEach(() -> {
					Parser p = new Parser(t);
					p.from(from);
					p.limit(to);
					Node again = p.one();
					Bench.sink = again;
				}, 50, 500, 40);
				Bench.Stats types = Bench.timeEach(() -> s.again(f), 50, 500,
						40);
				System.out.println("C one function (" + (f == median
						? "median" : "largest") + ", " +
						(t.lineOf(f.end) - t.lineOf(f.start) + 1) +
						" lines): parse " + Bench.fmtUs(one.medianMs()) +
						", names and types " +
						Bench.fmtUs(types.medianMs()));
			}
		}
	}
}
