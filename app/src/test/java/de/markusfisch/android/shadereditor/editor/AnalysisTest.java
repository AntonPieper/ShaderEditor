package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import de.markusfisch.android.shadereditor.syntax.Style;

public class AnalysisTest {
	@Test
	public void analysesTheWholeTextForItsRevision() {
		Analysis analysis = Analysis.of(Text.of(
				"struct Ray { vec3 o; };\nRay\nr;\nfloat f() { return 1.; }"),
				7, Analysis.NONE);
		assertEquals(7, analysis.revision());
		assertEquals(Set.of("Ray"), analysis.typeNames());
		assertEquals(List.of("Ray", "r", "f"), names(analysis));
		// The type guess stops at the line end; the outline colours it.
		assertEquals(Style.TYPE, analysis.tokens().style(
				analysis.tokens().indexAt(24)));
	}

	@Test
	public void keepsTheSetOfTypeNamesWhileTheyStayTheSame() {
		Analysis first = Analysis.of(Text.of("struct A { int a; };"), 0,
				Analysis.NONE);
		Analysis second = Analysis.of(Text.of("struct A { int b; };"), 1,
				first);
		assertSame(first.typeNames(), second.typeNames());
		Analysis third = Analysis.of(Text.of("struct B { int b; };"), 2,
				second);
		assertEquals(Set.of("B"), third.typeNames());
	}

	@Test
	public void findsWhereAddUniformInserts() {
		String text = "#ifdef GL_ES\n#endif\nuniform float a;\n" +
				"  uniform vec2 b;\nvoid main() {}\n";
		Analysis analysis = Analysis.of(Text.of(text), 0, Analysis.NONE);
		assertEquals(text.indexOf(";\nvoid") + 1, analysis.uniformLineEnd());
		assertEquals(text.indexOf("\nuniform"), analysis.endifEnd());
		Analysis none = Analysis.of(Text.of("void main() {}"), 0,
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
				Runnable::run, main, results::add);
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
		new AnalysisScheduler(document, 50, background, main, results::add);
		main.advance(0);
		background.runAll();
		// Done, not yet delivered when the next change comes.
		document.replace(0, 0, Text.of("x"));
		main.advance(0);
		assertEquals(List.of(), results);
		main.advance(50);
		document.replace(0, 0, Text.of("y"));
		main.advance(50);
		// The first of two queued tasks is stale and skips its work.
		assertEquals(2, background.tasks.size());
		background.runAll();
		main.advance(0);
		assertEquals(1, results.size());
		assertEquals(2, results.get(0).revision());
	}

	@Test
	public void deliversNothingAfterClose() {
		Document document = new Document(Text.of("float a;"));
		Main main = new Main();
		Background background = new Background();
		List<Analysis> results = new ArrayList<>();
		AnalysisScheduler scheduler = new AnalysisScheduler(document, 50,
				background, main, results::add);
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
}
