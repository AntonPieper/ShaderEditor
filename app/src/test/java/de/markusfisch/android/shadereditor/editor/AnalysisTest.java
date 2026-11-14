package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.syntax.BundledShaders;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.Outline;
import de.markusfisch.android.shadereditor.syntax.Syntax;
import de.markusfisch.android.shadereditor.syntax.Tokens;

public class AnalysisTest {
	private static final Syntax GLSL = GlslSyntax.INSTANCE;

	@Test
	public void analysesTheWholeTextForItsRevision() {
		Analysis analysis = Analysis.of(GLSL, Text.of(
				"struct Ray { vec3 o; };\nRay\nr;\nfloat f() { return 1.; }"),
				7, Analysis.NONE);
		assertEquals(7, analysis.revision());
		assertEquals(Set.of("Ray"), analysis.typeNames());
		assertEquals(List.of("Ray", "r", "f"), names(analysis));
	}

	@Test
	public void readsLineByLineAsTheWholeTextWouldBeRead()
			throws IOException {
		List<String> texts = new ArrayList<>(BundledShaders.read().values());
		String[] fragments = {"#if 0\n", "#else\n", "#endif\n", "#define ",
				"struct ", "uniform ", "float ", "Ray ", "x", "(", ")", "{",
				"}", "[", "]", ";", ",", "=", "/*", "*/", "//", "\n", "\r\n",
				"\\\n", " "};
		Random random = new Random(5);
		for (int n = 0; n < 5000; ++n) {
			StringBuilder sb = new StringBuilder();
			for (int i = random.nextInt(40); i-- > 0; ) {
				sb.append(fragments[random.nextInt(fragments.length)]);
			}
			texts.add(sb.toString());
		}
		for (String text : texts) {
			Text document = Text.of(text);
			Analysis analysis = Analysis.of(GLSL, document, 0, Analysis.NONE);
			// The text as the document holds it, with its line ends.
			text = document.toString();
			List<Outline.Declaration> declarations = Outline.of(
					GlslLexer.lex(text), text).declarations();
			Set<String> typeNames = new HashSet<>();
			for (Outline.Declaration declaration : declarations) {
				if (declaration.kind() == Outline.Kind.TYPE) {
					typeNames.add(declaration.name());
				}
			}
			assertEquals(text, declarations,
					analysis.outline().declarations());
			assertEquals(text, typeNames, analysis.typeNames());
			assertEquals(text, Analysis.uniformLineEnd(text),
					analysis.uniformLineEnd());
			assertEquals(text, Analysis.endifEnd(text), analysis.endifEnd());
		}
	}

	@Test
	public void keepsTheFirstDeclarationsOfAHugeText() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 300000; ++i) {
			sb.append("float v").append(i).append(" = 1.0;\n");
		}
		Analysis analysis = Analysis.of(GLSL, Text.of(sb), 0, Analysis.NONE);
		List<Outline.Declaration> declarations =
				analysis.outline().declarations();
		assertEquals(Analysis.MAX_DECLARATIONS, declarations.size());
		assertEquals("v" + (Analysis.MAX_DECLARATIONS - 1),
				declarations.get(declarations.size() - 1).name());
		for (Field field : Analysis.class.getDeclaredFields()) {
			Class<?> type = field.getType();
			assertFalse(field.getName(), type.isArray() ||
					CharSequence.class.isAssignableFrom(type) ||
					Text.class.isAssignableFrom(type) ||
					Tokens.class.isAssignableFrom(type));
		}
	}

	@Test
	public void keepsDeclarationsWhileTheirNamesFitTheBudget() {
		int length = 1000;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 200; ++i) {
			sb.append("float ").append("n".repeat(length - 3))
					.append(String.format(Locale.ROOT, "%03d", i))
					.append(";\n");
		}
		Analysis analysis = Analysis.of(GLSL, Text.of(sb), 0, Analysis.NONE);
		assertEquals(Analysis.MAX_NAME_CHARS / length,
				analysis.outline().declarations().size());
	}

	@Test
	public void leavesTheOutlineEmptyWithATooLongLine() {
		String declarations = "uniform float a;\nstruct A { int b; };\n";
		String fits = declarations + "x".repeat(Analysis.MAX_LINE_LENGTH);
		assertEquals(2, Analysis.of(GLSL, Text.of(fits), 0, Analysis.NONE)
				.outline().declarations().size());
		String tooLong = fits + "x";
		Analysis analysis = Analysis.of(GLSL, Text.of(tooLong), 0,
				Analysis.NONE);
		assertEquals(List.of(), analysis.outline().declarations());
		assertEquals(Set.of(), analysis.typeNames());
		assertEquals(declarations.indexOf("\n"), analysis.uniformLineEnd());
	}

	@Test
	public void keepsTheSetOfTypeNamesWhileTheyStayTheSame() {
		Analysis first = Analysis.of(GLSL, Text.of("struct A { int a; };"), 0,
				Analysis.NONE);
		Analysis second = Analysis.of(GLSL, Text.of("struct A { int b; };"), 1,
				first);
		assertSame(first.typeNames(), second.typeNames());
		Analysis third = Analysis.of(GLSL, Text.of("struct B { int b; };"), 2,
				second);
		assertEquals(Set.of("B"), third.typeNames());
	}

	@Test
	public void findsWhereAddUniformInserts() {
		String text = "#ifdef GL_ES\n#endif\nuniform float a;\n" +
				"  uniform vec2 b;\nvoid main() {}\n";
		Analysis analysis = Analysis.of(GLSL, Text.of(text), 0, Analysis.NONE);
		assertEquals(text.indexOf(";\nvoid") + 1, analysis.uniformLineEnd());
		assertEquals(text.indexOf("\nuniform"), analysis.endifEnd());
		Analysis none = Analysis.of(GLSL, Text.of("void main() {}"), 0,
				Analysis.NONE);
		assertEquals(-1, none.uniformLineEnd());
		assertEquals(-1, none.endifEnd());
	}

	@Test
	public void analysesAfterTheDelaySinceTheLastChange() {
		Document document = new Document(Text.of("float a;"));
		Main main = new Main();
		List<Analysis> results = new ArrayList<>();
		AnalysisScheduler scheduler = new AnalysisScheduler(document, 50,
				Runnable::run, main, results::add, () -> main.now,
				AnalysisTest::analyse);
		main.advance(0);
		assertEquals(1, results.size());
		document.replace(0, 0, Text.of("x"));
		main.advance(40);
		document.replace(0, 0, Text.of("y"));
		main.advance(40);
		assertEquals(1, results.size());
		main.advance(10);
		assertEquals(2, results.size());
		assertEquals(2, results.get(1).revision());
		assertEquals(results.get(1), scheduler.latest());
		document.select(1, 1);
		main.advance(100);
		assertEquals(2, results.size());
	}

	@Test
	public void dropsResultsForRevisionsNoLongerCurrent() {
		Document document = new Document(Text.of("float a;"));
		Main main = new Main();
		Background background = new Background();
		List<Analysis> results = new ArrayList<>();
		new AnalysisScheduler(document, 50, background, main, results::add,
				() -> main.now, AnalysisTest::analyse);
		main.advance(0);
		background.runAll();
		// Done, not yet delivered when the next change comes.
		document.replace(0, 0, Text.of("x"));
		main.advance(0);
		assertEquals(List.of(), results);
		main.advance(50);
		document.replace(0, 0, Text.of("y"));
		main.advance(50);
		// The pass for "x" sees the change and skips its work.
		assertEquals(1, background.tasks.size());
		background.runAll();
		main.advance(0);
		background.runAll();
		main.advance(0);
		assertEquals(1, results.size());
		assertEquals(2, results.get(0).revision());
	}

	@Test
	public void givesUpAtTheStartOfALineWhenCancelled() {
		int[] asked = new int[1];
		assertNull(Analysis.of(GLSL, Text.of("float a;\n".repeat(10)), 0,
				Analysis.NONE, () -> ++asked[0] == 3));
		assertEquals(3, asked[0]);
	}

	@Test
	public void pacesPassesByHowLongTheLastOneTook() {
		Document document = new Document(Text.of("float a;"));
		Main main = new Main();
		Background background = new Background();
		int[] passes = new int[1];
		List<Analysis> results = new ArrayList<>();
		new AnalysisScheduler(document, 50, background, main, results::add,
				() -> main.now, (text, revision, previous, cancel) -> {
			++passes[0];
			main.now += 150;
			return Analysis.of(GLSL, text, revision, previous);
		});
		// A key every 100 ms for a minute.
		typeUntil(document, main, background, 60_000, 100);
		assertTrue(passes[0] <= 60_000 / (150 * 9) + 1);
		int during = passes[0];
		int resultsDuring = results.size();
		assertTrue(resultsDuring > 10);
		// Exactly one more once the keys stop, delivered within the
		// spacing.
		typeUntil(document, main, background, main.now + 10_000, 0);
		assertEquals(during + 1, passes[0]);
		assertEquals(resultsDuring + 1, results.size());
		assertEquals(document.revision(), results.get(results.size() - 1)
				.revision());
	}

	@Test
	public void abandonsAPassOnceTheDocumentChanges() {
		Document document = new Document(Text.of("float a;"));
		Main main = new Main();
		Background background = new Background();
		List<Boolean> cancelled = new ArrayList<>();
		List<Analysis> results = new ArrayList<>();
		new AnalysisScheduler(document, 50, background, main, results::add,
				() -> main.now, (text, revision, previous, cancel) -> {
			if (cancelled.isEmpty()) {
				// A key arrives while the pass runs.
				document.replace(0, 0, Text.of("x"));
			}
			cancelled.add(cancel.cancelled());
			return cancel.cancelled()
					? null
					: Analysis.of(GLSL, text, revision, previous);
		});
		main.advance(0);
		background.runAll();
		main.advance(0);
		assertEquals(List.of(true), cancelled);
		assertEquals(List.of(), results);
		main.advance(50);
		background.runAll();
		main.advance(0);
		assertEquals(List.of(true, false), cancelled);
		assertEquals(1, results.size());
		assertEquals(document.revision(), results.get(0).revision());
	}

	@Test
	public void keepsTheShortDelayForAFastDocument() {
		Document document = new Document(Text.of("float a;\n".repeat(500)));
		Main main = new Main();
		Background background = new Background();
		List<Analysis> results = new ArrayList<>();
		new AnalysisScheduler(document, 50, background, main, results::add,
				() -> main.now, (text, revision, previous, cancel) -> {
			main.now += 5;
			return Analysis.of(GLSL, text, revision, previous);
		});
		for (int i = 0; i < 3; ++i) {
			typeUntil(document, main, background, main.now + 1000, 100);
			document.replace(0, 0, Text.of("x"));
			typeUntil(document, main, background, main.now + 49, 0);
			int before = results.size();
			typeUntil(document, main, background, main.now + 1, 0);
			assertEquals(before + 1, results.size());
			assertEquals(document.revision(), results.get(before).revision());
		}
	}

	// Types a key every keyMillis, or none if 0, and runs the background
	// work as it comes.
	private static void typeUntil(Document document, Main main,
			Background background, long until, long keyMillis) {
		long next = main.now;
		while (main.now < until) {
			if (keyMillis > 0 && main.now >= next) {
				document.replace(0, 0, Text.of("x"));
				next += keyMillis;
			}
			main.advance(1);
			background.runAll();
			main.advance(0);
		}
	}

	@Test
	public void deliversNothingAfterClose() {
		Document document = new Document(Text.of("float a;"));
		Main main = new Main();
		Background background = new Background();
		List<Analysis> results = new ArrayList<>();
		AnalysisScheduler scheduler = new AnalysisScheduler(document, 50,
				background, main, results::add, () -> main.now,
				AnalysisTest::analyse);
		main.advance(0);
		scheduler.close();
		background.runAll();
		document.replace(0, 0, Text.of("x"));
		main.advance(100);
		background.runAll();
		main.advance(0);
		assertEquals(List.of(), results);
		assertEquals(0, background.tasks.size());
	}

	private static List<String> names(Analysis analysis) {
		List<String> names = new ArrayList<>();
		analysis.outline().declarations().forEach(d -> names.add(d.name()));
		return names;
	}

	// A main thread with a clock that only moves when told to.
	private static final class Main implements AnalysisScheduler.MainThread {
		private final List<Object[]> tasks = new ArrayList<>();
		private long now;

		@Override
		public void post(Runnable task, long delayMillis) {
			tasks.add(new Object[]{now + delayMillis, task});
		}

		@Override
		public void cancel(Runnable task) {
			tasks.removeIf(t -> t[1] == task);
		}

		void advance(long millis) {
			now += millis;
			for (Object[] due; (due = next()) != null; ) {
				tasks.remove(due);
				((Runnable) due[1]).run();
			}
		}

		private Object[] next() {
			Object[] next = null;
			for (Object[] task : tasks) {
				if ((long) task[0] <= now &&
						(next == null || (long) task[0] < (long) next[0])) {
					next = task;
				}
			}
			return next;
		}
	}

	private static final class Background implements Executor {
		private final List<Runnable> tasks = new ArrayList<>();

		@Override
		public void execute(Runnable task) {
			tasks.add(task);
		}

		void runAll() {
			List<Runnable> run = new ArrayList<>(tasks);
			tasks.clear();
			run.forEach(Runnable::run);
		}
	}

	private static Analysis analyse(Text text, int revision,
			Analysis previous, Analysis.Cancel cancel) {
		return Analysis.of(GLSL, text, revision, previous,
				cancel);
	}
}
