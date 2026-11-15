package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Analysis;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.LineStyles;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Syntax;

/**
 * SPIKE, throwaway: what the editor's analysis costs today, measured
 * with the production classes. Question 1 of the study.
 */
final class Baseline {
	private Baseline() {
	}

	public static void main(String[] args) {
		System.out.println("# Baseline: the production classes as they are");
		System.out.println(Bench.machine());
		int[] sizes = {1000, 5000, 50000};
		for (String corpus : new String[]{"repeated", "generated"}) {
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
		System.out.println();
		System.out.println("## " + corpus + ", " + lines + " lines, " +
				source.length() + " chars");

		// The whole-file analysis as AnalysisScheduler runs it.
		Bench.Stats analysis = Bench.time(() -> Analysis.of(syntax, text,
				1, null), 1500, 40);
		Analysis a = Analysis.of(syntax, text, 1, null);
		long kept = Bench.retained(() -> Analysis.of(syntax, text, 1,
				null), 8);
		System.out.println("whole-file analysis (lex + outline): " +
				analysis.ms() + ", allocates " + analysis.mb() +
				", keeps " + kept / 1024 + " KB, " +
				a.outline().declarations().size() + " declarations");

		// The states of every line, as the background continuation does.
		Bench.Stats states = Bench.time(() -> {
			LineTokens tokens = new LineTokens();
			int state = Syntax.TEXT_START;
			for (int n = 0; n < lines; ++n) {
				state = syntax.lexLine(text.line(n), n < lines - 1, state,
						tokens);
			}
			return state;
		}, 1500, 40);
		System.out.println("line states of the whole file (colour in " +
				"the background): " + states.ms());

		// A keypress in the middle with 50 lines shown, and what the
		// main thread does for it: the change, the line styles' update,
		// the runs of the shown lines and the completion strip.
		Document document = new Document(text);
		LineStyles styles = new LineStyles(document, syntax, 200);
		// Make every line current first.
		for (LineStyles.Continuation c; (c = styles.continuation()) !=
				null; ) {
			while (styles.apply(c.run(1 << 20))) {
				// Store all of it.
			}
		}
		int middle = lines / 2;
		int from = Math.max(0, middle - 25);
		int to = Math.min(lines, from + 50);
		Outline outline = a.outline();
		List<String> extra = new ArrayList<>();
		extra.add("resolution");
		extra.add("time");
		int[] words = {0};
		Text one = Text.of("a");
		Text none = Text.of("");
		Runnable key = () -> {
			int caret = document.text().lineStart(middle) +
					document.text().line(middle).length();
			document.replace(caret, caret, one);
			styles.show(from, to);
			for (int n = from; n < to; ++n) {
				Bench.sink = styles.runs(n);
			}
			int lineStart = document.text().lineStart(middle);
			syntax.complete(styles.runs(middle).line(),
					caret + 1 - lineStart, lineStart, outline, false, extra,
					(w, position) -> words[0] += w.size());
			// Take it out again so the line does not grow.
			document.replace(caret, caret + 1, none);
			styles.show(from, to);
			for (int n = from; n < to; ++n) {
				Bench.sink = styles.runs(n);
			}
		};
		Bench.Stats keypress = Bench.timeEach(key, 200, 1500, 200);
		System.out.println("one keypress on the main thread (type + " +
				"delete, halved): median " +
				Bench.fmtUs(keypress.medianMs() / 2) + ", p90 " +
				Bench.fmtUs(keypress.p90Ms() / 2) + ", worst batch " +
				Bench.fmtUs(keypress.maxMs() / 2) + ", allocates " +
				keypress.allocBytes() / 2 + " bytes");
		styles.close();
	}
}
