package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.junit.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;
import de.markusfisch.android.shadereditor.project.BuiltinNames;
import de.markusfisch.android.shadereditor.syntax.GlslLexer;
import de.markusfisch.android.shadereditor.syntax.LineTokens;
import de.markusfisch.android.shadereditor.project.FileTexts;
import de.markusfisch.android.shadereditor.project.OpenProject;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.tuning.TunedPlace;
import de.markusfisch.android.shadereditor.tuning.Tuner;
import de.markusfisch.android.shadereditor.tuning.TuningSession;
import de.markusfisch.android.shadereditor.tuning.TuningStep;
import de.markusfisch.android.shadereditor.widget.TuningBar;

// A session tunes the numbers of one file while the bar is open: each
// one tuned is a uniform of its builds, values show without a build,
// each number left is one step of undo, Revert puts all back as one, a
// number that must stay a constant is built as text when the finger
// rests. The document never holds a uniform.
public class TuningSessionTest {
	private static final String PRECISION = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif");
	private static final String SHADER = lines(PRECISION,
			"uniform float time;",
			"float glow(vec2 p) {",
			"	return smoothstep(0.26,",
			"			0.01, length(p - vec2(0.5)));",
			"}",
			"void main(void) {",
			"	float s = 0.0;",
			"	for (int i = 0; i < 3; ++i) {",
			"		s += 0.3 * sin(time) - 2.0;",
			"	}",
			"	gl_FragColor = vec4(s, 0.1, 0.2, 1.0) * glow(vec2(0.25));",
			"}");

	private final Document document = new Document(Text.of(SHADER));
	private final Map<String, String> files = new LinkedHashMap<>();
	private final List<Run> runs = new ArrayList<>();
	private final List<Runnable> preparing = new ArrayList<>();
	private final List<String> heard = new ArrayList<>();
	private int applied;
	private int edits;
	@Nullable
	private Runnable resting;
	private boolean waits;
	// A value typed into the bar and not yet taken, or null.
	@Nullable
	private String typed;
	private final Tuner tuner = new Tuner(new Host(), Runnable::run,
			Runnable::run);

	// What the engine was handed: the text of main.frag, the uniforms
	// with their values, and who hears whether it draws.
	private record Run(@NonNull String text, boolean temporary,
			@NonNull List<Tuning> tunings, @Nullable Tuning.Listener heard) {
		@NonNull
		String values() {
			var values = new ArrayList<String>();
			for (Tuning tuning : tunings) {
				values.add(tuning.name() + "=" + tuning.value());
			}
			Collections.sort(values);
			return String.join(" ", values);
		}

		double value(@NonNull String name) {
			for (Tuning tuning : tunings) {
				if (tuning.name().equals(name)) {
					return tuning.value();
				}
			}
			throw new AssertionError("no " + name + " in " + values());
		}
	}

	@Test
	public void valuesShowWithoutABuildAndTheTextIsBuiltAtTheEnd() {
		var session = begin("0.3");
		assertEquals(1, runs.size());
		var run = runs.get(0);
		assertTrue(run.temporary);
		assertEquals(SHADER.replace("0.3 *", "(se_tune0) *"), run.text);
		assertEquals("se_tune0=0.3", run.values());
		run.heard.onLive();
		// Once as it draws, for a value set meanwhile.
		assertEquals(1, applied);

		for (int i = 1; i <= 100; ++i) {
			session.set((30 + i) + "e-2");
		}
		assertEquals(101, applied);
		assertEquals(1.3, run.tunings.get(0).value(), 1e-9);
		assertEquals(1, runs.size());
		// Nothing is written while the number is tuned.
		assertEquals(SHADER, text());

		// Revert: the value tried is a step, then the text as it was,
		// built once more.
		session.end(false);
		assertEquals(2, runs.size());
		assertEquals(SHADER, runs.get(1).text);
		assertFalse(runs.get(1).temporary);
		assertEquals(List.of(), runs.get(1).tunings);
		assertEquals(SHADER, text());
		assertEquals(2, edits);
		// The uniform has the text's value at once.
		assertEquals(0.3, run.tunings.get(0).value(), 0);
		document.undo();
		assertEquals(SHADER.replace("0.3 *", "130e-2 *"), text());
		// Nothing after the end.
		int shown = applied;
		session.set("2.0");
		assertEquals(shown, applied);
	}

	// F1: the GL thread wrote the first value and posted that the build
	// draws; a value set before main heard it must still be shown.
	@Test
	public void aValueSetWhileLiveIsOnItsWayShows() {
		var session = begin("0.3");
		session.set("0.6");
		assertEquals(0.6, runs.get(0).tunings.get(0).value(), 0);
		applied = 0;
		runs.get(0).heard.onLive();
		assertEquals(1, applied);
	}

	@Test
	public void doneWritesTheNumberOnceAndBuildsThatText() {
		var session = begin("0.3");
		session.set("0.42");
		session.end(true);
		String done = SHADER.replace("0.3 *", "0.42 *");
		assertEquals(done, text());
		assertEquals(1, edits);
		assertEquals(done, runs.get(1).text);
		assertFalse(runs.get(1).temporary);
		// One step: Undo after Done gives the text as it was.
		document.undo();
		assertEquals(SHADER, text());
		assertTrue(session.states().contains(document.state()));
	}

	// Two numbers of one line: the one left is written, and both are
	// uniforms of the next build, each where the text has it.
	@Test
	public void twoNumbersOfOneLine() {
		var session = begin("0.1, 0.2");
		live();
		session.set("0.15");
		var tuned = move("0.2, 1.0");
		assertEquals("0.2", tuned.text());
		assertEquals(SHADER.replace("0.1, 0.2", "0.15, 0.2"), text());
		assertEquals(1, runs.size());
		rest();
		assertEquals(SHADER.replace("0.1, 0.2", "(se_tune0), (se_tune1)"),
				runs.get(1).text);
		assertEquals("se_tune0=0.15 se_tune1=0.2", runs.get(1).values());
		runs.get(1).heard.onLive();
		session.set("0.25");
		assertEquals(2, runs.size());
		session.end(true);
		assertEquals(SHADER.replace("0.1, 0.2", "0.15, 0.25"), text());
		assertEquals(2, edits);
	}

	// Five numbers, two of a call written over two lines: every one
	// changed is a uniform of the build, at its place; Done writes
	// exactly them, Revert puts back the text byte for byte.
	@Test
	public void fiveNumbersAndRevert() {
		String[] numbers = {"0.26,", "0.01,", "0.5)", "0.1,", "0.25)"};
		String[] values = {"0.3", "0.02", "0.45", "0.125", "0.2"};
		var session = begin(numbers[0]);
		live();
		for (int i = 0; i < numbers.length; ++i) {
			if (i > 0) {
				move(numbers[i]);
			}
			session.set(values[i]);
		}
		rest();
		var build = runs.get(runs.size() - 1);
		assertEquals(SHADER
						.replace("0.26,\n\t\t\t0.01,",
								"(se_tune0),\n\t\t\t(se_tune2),")
						.replace("vec2(0.5)", "vec2((se_tune3))")
						.replace("s, 0.1,", "s, (se_tune4),")
						.replace("vec2(0.25)", "vec2((se_tune1))"),
				build.text);
		// The tuned number is named first.
		assertEquals("se_tune0=0.3 se_tune1=0.2 se_tune2=0.02 " +
				"se_tune3=0.45 se_tune4=0.125", build.values());
		// Four were left and written, the fifth is tuned.
		assertEquals(4, edits);
		session.end(false);
		assertEquals(SHADER, text());
		assertEquals(6, edits);
		// Revert is one step, after the fifth's.
		document.undo();
		assertEquals(SHADER
				.replace("0.26,\n\t\t\t0.01,", "0.3,\n\t\t\t0.02,")
				.replace("vec2(0.5)", "vec2(0.45)")
				.replace("s, 0.1,", "s, 0.125,")
				.replace("vec2(0.25)", "vec2(0.2)"), text());
	}

	// What was visited without a change is not written, and a number
	// brought back to its value, however written, has its text as it
	// was, byte for byte.
	@Test
	public void doneWritesExactlyTheChangedNumbers() {
		document.replace(0, 0, Text.of("// 0.10\n"));
		String start = text();
		var session = begin("0.26,");
		move("0.5)");
		session.set("0.75");
		move("0.25)");
		session.set("0.5");
		session.set("0.250");
		move("0.1,");
		session.set("0.100");
		move("0.2,");
		session.set("0.3");
		session.end(true);
		assertEquals(start.replace("vec2(0.5)", "vec2(0.75)")
				.replace("0.1, 0.2,", "0.1, 0.3,"), text());
		assertEquals(2, edits);
	}

	// A negative value after a binary minus is written in parentheses,
	// and Revert takes them away again.
	@Test
	public void aNegativeValueAfterAMinusIsRevertedByteForByte() {
		var session = begin("2.0;");
		session.set("(-1.5)");
		move("0.3 *");
		assertEquals(SHADER.replace("- 2.0;", "- (-1.5);"), text());
		rest();
		var build = runs.get(runs.size() - 1);
		assertTrue(build.text, build.text.contains(
				"(se_tune1) * sin(time) - (se_tune0);"));
		assertEquals(-1.5, build.value("se_tune0"), 0);
		// The bar reads it as the number it tuned.
		var tuned = move("1.5)");
		assertEquals("(-1.5)", tuned.text());
		assertEquals("2.0", tuned.start());
		session.end(false);
		assertEquals(SHADER, text());
	}

	// Each visit that leaves a number changed is one step, a constant's
	// as a uniform's, also when the same number is changed again later.
	// Done writes the value tried as its step; Revert does too, then
	// puts every number back as one more. Undo and Redo walk them all.
	@Test
	public void undoAndRedoWalkEveryStepAfterDone() {
		String[] texts = changeFourTimes();
		session.end(true);
		walk(texts);
	}

	@Test
	public void undoAndRedoWalkEveryStepAfterRevert() {
		String[] texts = changeFourTimes();
		session.end(false);
		assertEquals(SHADER, text());
		String[] all = Arrays.copyOf(texts, texts.length + 1);
		all[texts.length] = SHADER;
		walk(all);
	}

	private TuningSession session;

	// The texts after each step: 0.1 and 0.2 changed once, the loop's
	// constant twice with another number between.
	@NonNull
	private String[] changeFourTimes() {
		session = begin("0.1,");
		live();
		session.set("0.15");
		move("3;");
		session.set("5");
		rest();
		rest();
		move("0.2,");
		session.set("0.25");
		move("5;");
		session.set("6");
		String one = SHADER.replace("0.1, 0.2", "0.15, 0.2");
		String two = one.replace("i < 3;", "i < 5;");
		String three = two.replace("0.15, 0.2", "0.15, 0.25");
		return new String[]{SHADER, one, two, three,
				three.replace("i < 5;", "i < 6;")};
	}

	// Undo from the last of texts to the first, one step each, and Redo
	// back.
	private void walk(@NonNull String[] texts) {
		assertEquals(texts[texts.length - 1], text());
		for (int i = texts.length - 1; i-- > 0; ) {
			document.undo();
			assertEquals("undo to " + i, texts[i], text());
		}
		assertFalse(document.canUndo());
		for (int i = 1; i < texts.length; ++i) {
			document.redo();
			assertEquals("redo to " + i, texts[i], text());
		}
		assertFalse(document.canRedo());
	}

	// Undo while the bar is open takes back the value tried, then the
	// number left before; the bar follows, the values show without a
	// build, and the edit made before tuning stays out of reach.
	@Test
	public void undoAndRedoWalkTheSessionsSteps() {
		document.replace(0, 0, Text.of("// before\n"));
		String before = text();
		var session = begin("0.1,");
		live();
		session.set("0.15");
		move("0.2,");
		rest();
		runs.get(1).heard.onLive();
		session.set("0.25");
		int built = runs.size();
		var uniforms = runs.get(1);

		session.undo();
		assertEquals(before.replace("0.1,", "0.15,"), text());
		assertEquals("tuned 0.2 from 0.2", heard.get(heard.size() - 1));
		assertEquals(0.2, uniforms.value("se_tune1"), 0);
		session.undo();
		assertEquals(before, text());
		assertEquals("tuned 0.1 from 0.1", heard.get(heard.size() - 1));
		assertEquals(0.1, uniforms.value("se_tune0"), 0);
		assertFalse(session.canUndo());
		session.undo();
		assertEquals(before, text());

		assertTrue(session.canRedo());
		session.redo();
		assertEquals("tuned 0.15 from 0.1", heard.get(heard.size() - 1));
		assertEquals(0.15, uniforms.value("se_tune0"), 0);
		session.redo();
		assertEquals(before.replace("0.1, 0.2", "0.15, 0.25"), text());
		assertFalse(session.canRedo());
		// No build for any of it: the numbers are uniforms.
		assertEquals(built, runs.size());

		// A new value ends what Redo could make again.
		session.undo();
		session.set("0.3");
		assertFalse(session.canRedo());
		session.end(true);
		assertEquals(before.replace("0.1, 0.2", "0.15, 0.3"), text());
		// Undo after Done walks the same steps, between states the
		// session made.
		document.undo();
		assertTrue(session.states().contains(document.state()));
		document.undo();
		assertTrue(session.states().contains(document.state()));
		assertEquals(before, text());
		document.undo();
		assertFalse(session.states().contains(document.state()));
	}

	// A number that must stay a constant is found when the build that
	// tries it is answered, off the main thread: nothing of that build
	// runs, and the next one has it as its text, with the numbers that
	// are uniforms, when the finger rests.
	@Test
	public void aConstantAmongUniforms() {
		var session = begin("0.3 *");
		live();
		session.set("0.5");
		move("3;");
		session.set("4");
		session.set("5");
		rest();
		assertEquals("constant", heard.get(heard.size() - 1));
		assertEquals(1, runs.size());
		rest();
		var build = runs.get(1);
		assertEquals(SHADER.replace("0.3 *", "(se_tune0) *")
				.replace("i < 3;", "i < 5;"), build.text);
		assertEquals("se_tune0=0.5", build.values());
		// Its errors are reported as any build's.
		assertNull(build.heard);
		session.end(true);
		assertEquals(SHADER.replace("0.3 *", "0.5 *")
				.replace("i < 3;", "i < 5;"), text());
	}

	// The tokens say so: nothing is built until a text is asked for.
	@Test
	public void aLoopCountOfGlslEs100IsBuiltAsText() {
		var session = begin("3;");
		assertEquals(List.of(), runs);
		assertEquals(List.of("constant"), heard);
		session.set("5");
		rest();
		assertEquals(1, runs.size());
		assertTrue(runs.get(0).text.contains("i < 5;"));
		assertEquals(List.of(), runs.get(0).tunings);
		// Revert builds the text, as a build of the session ran.
		session.end(false);
		assertEquals(SHADER, runs.get(1).text);
	}

	// The compiler refused: the build before draws on, and the number is
	// built with its text when the finger rests.
	@Test
	public void aRefusedUniformIsBuiltAsText() {
		var session = begin("0.3");
		session.set("0.5");
		runs.get(0).heard.onRefused(List.of(
				ProjectDiagnostic.error("must be constant")));
		assertEquals(List.of("constant"), heard);
		rest();
		assertEquals(2, runs.size());
		assertEquals(List.of(), runs.get(1).tunings);
		assertTrue(runs.get(1).text.contains("s += 0.5 * sin(time)"));
	}

	// While a build is prepared, the text changes when a number is left:
	// the project drops it, and it is asked for again.
	@Test
	public void aBuildDroppedByAWriteIsAskedForAgain() {
		waits = true;
		var session = begin("0.1,");
		session.set("0.15");
		move("0.2,");
		assertNotNull(resting);
		rest();
		settle();
		assertEquals(1, runs.size());
		assertEquals("se_tune0=0.15 se_tune1=0.2", runs.get(0).values());
		session.end(true);
	}

	// Undo while a build is prepared: the project drops it, as the text
	// changed, and the one asked for again has the numbers as they are;
	// the number only visited is forgotten.
	@Test
	public void aBuildDroppedByUndoIsAskedForAgain() {
		waits = true;
		var session = begin("0.1,");
		settle();
		live();
		session.set("0.15");
		move("0.2,");
		rest();
		assertEquals(1, preparing.size());
		session.undo();
		assertEquals("tuned 0.1 from 0.1", heard.get(heard.size() - 1));
		rest();
		settle();
		assertEquals(2, runs.size());
		assertEquals("se_tune0=0.1", runs.get(1).values());
		assertEquals(SHADER, runs.get(1).text.replace("(se_tune0)", "0.1"));
		session.end(true);
		assertEquals(SHADER, text());
	}

	// The text has a name of ours: one build more, with names above it.
	@Test
	public void aNameTheTextHasIsNotTaken() {
		document.replace(0, 0, Text.of("// se_tune0 se_tune7\n"));
		begin("0.3");
		assertEquals(1, runs.size());
		assertEquals("se_tune1=0.3", runs.get(0).values());
		assertTrue(runs.get(0).text.contains("s += (se_tune1) * sin(time)"));
	}

	@Test
	public void theUniformIsNeverWritten() {
		var session = begin("0.3");
		assertThrows(IllegalArgumentException.class,
				() -> session.set("se_tune0"));
		session.end(true);
		assertEquals(SHADER, text());
	}

	// Review: an edit from outside in front of the tuned number made it
	// another token; the session writes nothing there, and the tried
	// value goes. Numbers written before keep their values.
	@Test
	public void anOutsideEditAtANumberLetsItGoUnwritten() {
		var session = begin("0.2,");
		session.set("0.25");
		move("0.3 *");
		session.set("0.45");
		int at = text().indexOf("0.3 *");
		document.replace(at, at, Text.of("1"));
		session.end(true);
		assertTrue(text(), text().contains("s += 10.3 * sin(time)"));
		assertTrue(text(), text().contains("vec4(s, 0.1, 0.25, 1.0)"));
		// One right after it, too.
		var again = begin("0.1, 0.25");
		again.set("0.15");
		int end = text().indexOf("0.1, 0.25") + 3;
		document.replace(end, end, Text.of("5"));
		again.end(true);
		assertTrue(text(), text().contains("vec4(s, 0.15, 0.25"));
	}

	// Review: what an outside edit does not touch stays where it is;
	// a number it cuts into is not written.
	@Test
	public void outsideEditsBeforeOrIntoANumber() {
		var session = begin("0.3 *");
		session.set("0.456");
		document.replace(0, 0, Text.of("// inserted\n"));
		session.end(true);
		assertEquals("// inserted\n" + SHADER.replace("0.3 *", "0.456 *"),
				text());
		session = begin("0.456 *");
		session.set("0.75");
		int at = text().indexOf("0.456 *");
		document.replace(at + 2, at + 4, Text.of("98"));
		String outside = text();
		session.end(true);
		assertEquals(outside, text());
	}

	// Review: a write the document refuses (it would grow past its
	// largest size) is no step: Undo does not reach the edit made before
	// the bar opened.
	@Test
	public void aRefusedWriteIsNoStep() {
		String large = "1.//" + "x".repeat((int) Document.MAX_BYTES - 5);
		document.replace(0, text().length(), Text.of(large));
		document.replace(large.length(), large.length(), Text.of("y"));
		long before = document.text().length();
		var session = new Tuner(new Host() {
			@Override
			public void prepare(
					@NonNull List<OpenProject.Replacement> parts,
					@NonNull FileTexts.Callback<Preparation> done) {
			}

			@Override
			public void prepare(@NonNull FileTexts.Callback<Preparation> done) {
			}
		}, Runnable::run, Runnable::run).begin(document, path("main.frag"),
				TunedPlace.at("1.//", 0), listener());
		session.set("1.25");
		assertTrue(session.canUndo());
		session.undo();
		assertEquals(before, document.text().length());
		assertFalse(session.canUndo());
		session.end(true);
		assertEquals(before, document.text().length());
	}

	// Review: return-1. tuned: the uniform is in parentheses in the
	// build, and a value written without its minus keeps apart from the
	// name before it.
	@Test
	public void aNumberRightAfterANameKeepsApart() {
		String joined = SHADER.replace("return smoothstep",
				"if (p.x < 0.) return-1.; return smoothstep");
		document.replace(0, text().length(), Text.of(joined));
		var session = begin("1.; return");
		assertTrue(heard.toString(), session.isOpen());
		// The minus is the number's: the uniform holds -1.
		assertTrue(runs.get(0).text,
				runs.get(0).text.contains("return(se_tune0);"));
		assertEquals(-1, runs.get(0).value("se_tune0"), 0);
		var place = session.tuned().place();
		session.set(place.text(new BigDecimal("2"), BigDecimal.ONE));
		session.end(true);
		assertTrue(text(), text().contains("return 2.;"));
		document.undo();
		assertEquals(joined, text());
	}

	// Review: a value its type cannot hold is never taken, so the bar
	// never formats one; the ruler stops at the ends of the type.
	@Test
	public void aValueOutsideItsTypeIsNotTaken() {
		document.replace(0, text().length(), Text.of(SHADER.replace(
				"float s = 0.0;", "uvec2 u = uvec2(4294967295u, 1u); " +
						"float s = 0.0;")));
		var session = begin("4294967295u");
		session.set("4294967296u");
		move("1u);");
		assertTrue(text(), text().contains("uvec2(4294967295u, 1u)"));
		session.undo();
		session.redo();
		var tuned = session.tuned();
		new TuningBar.Tuned(tuned.place(), tuned.startValue(),
				tuned.value(), BigDecimal.ONE, BigDecimal.ONE,
				tuned.style()).text();
		assertEquals(new BigDecimal("4294967295"), TuningStep.move(
				new BigDecimal("4294967295"), BigDecimal.ONE, 1,
				Tuning.Type.UINT));
	}

	// Review: an older build's refusal that arrives after a newer build
	// was handed over changes nothing; the newer one's uniforms show.
	@Test
	public void aLateRefusalOfAnOlderBuildChangesNothing() {
		var session = begin("0.1,");
		var older = runs.get(0);
		session.set("0.15");
		move("0.2,");
		rest();
		var newer = runs.get(1);
		older.heard.onRefused(List.of(ProjectDiagnostic.error("lost")));
		newer.heard.onLive();
		move("0.15,");
		session.set("0.16");
		assertEquals(0.16, newer.value("se_tune0"), 0);
		assertEquals(List.of(), heard);
	}

	// Only the newest build handed over counts: an older one that draws
	// late does not make its uniform drawn while the newer one, which
	// tries it with another, is refused.
	@Test
	public void aLateLiveOfAnOlderBuildChangesNothing() {
		var session = begin("0.1,");
		var older = runs.get(0);
		session.set("0.15");
		move("0.2,");
		rest();
		var newer = runs.get(1);
		older.heard.onLive();
		newer.heard.onRefused(List.of(ProjectDiagnostic.error("refused")));
		assertEquals(List.of(), heard);
		rest();
		assertEquals(1, runs.get(2).tunings.size());
	}

	// Review: a build that drew and is refused later, as when the context
	// was lost and its restore failed, is asked for again; that build's
	// errors are told as any build's.
	@Test
	public void aBuildRefusedAfterItDrewIsAskedForAgain() {
		var session = begin("0.3");
		live();
		runs.get(0).heard.onRefused(List.of(ProjectDiagnostic.error("lost")));
		session.set("0.6");
		rest();
		assertEquals(2, runs.size());
		assertNull(runs.get(1).heard);
		assertEquals("se_tune0=0.6", runs.get(1).values());
	}

	// Review: an unchanged loop count found constant by the first build
	// strands nothing: the other number tried is built.
	@Test
	public void aConstantFoundFirstStrandsNothing() {
		waits = true;
		var session = begin("0.3 *");
		session.set("0.4");
		move("3;");
		rest();
		settle();
		rest();
		settle();
		assertFalse(runs.isEmpty());
		assertEquals("se_tune0=0.4", runs.get(runs.size() - 1).values());
		move("0.4 *");
		session.set("0.45");
		assertEquals(0.45, runs.get(runs.size() - 1).value("se_tune0"), 0);
	}

	// Review: when several uniforms tried together are refused, none is
	// known to be the cause: they are tried one at a time, the tuned one
	// first, and only one refused alone is a constant.
	@Test
	public void severalRefusedTogetherAreTriedOneAtATime() {
		waits = true;
		var session = begin("0.1,");
		session.set("0.15");
		move("0.2,");
		rest();
		settle();
		var both = runs.get(0);
		assertEquals("se_tune0=0.15 se_tune1=0.2", both.values());
		both.heard.onRefused(List.of(ProjectDiagnostic.error("refused")));
		rest();
		settle();
		var one = runs.get(1);
		assertEquals(1, one.tunings.size());
		assertTrue(one.text, one.text.contains("vec4(s, 0.15, (se_tune"));
		one.heard.onLive();
		session.set("0.25");
		assertEquals(List.of(), heard);
		rest();
		settle();
		assertEquals(2, runs.get(runs.size() - 1).tunings.size());
	}

	// Review: a build the project drops when another document changes
	// is asked for again once the project tells so.
	@Test
	public void aBuildDroppedByTheProjectIsAskedForAgain() {
		waits = true;
		var session = begin("0.3 *");
		preparing.clear();
		session.projectChanged();
		rest();
		settle();
		assertEquals(1, runs.size());
	}

	// Review: the text's build at the end retires the session's even
	// when an edit came before it was delivered.
	@Test
	public void theEndBuildComesAfterALaterEdit() {
		var session = begin("0.1,");
		live();
		session.set("0.65");
		move("0.2,");
		rest();
		live();
		waits = true;
		session.end(false);
		document.replace(0, 0, Text.of("// an edit\n"));
		settle();
		var last = runs.get(runs.size() - 1);
		assertFalse(last.temporary);
		assertEquals(SHADER, last.text);
	}

	// Review: a number keeps what tells it from the others while an
	// earlier one grows, so its chosen step stays with it.
	@Test
	public void aNumberStaysItselfWhileAnEarlierOneGrows() {
		var session = begin("0.2, 1.0");
		int id = session.tuned().id();
		move("0.1, 0.2");
		session.set("0.125");
		assertEquals(id, move("0.2, 1.0").id());
	}

	// Review: unusual spellings are written and walked back byte for
	// byte.
	@Test
	public void unusualSpellingsRoundTrip() {
		String odd = SHADER.replace("0.3 * sin(time) - 2.0",
				"1. + +.5 + 1e3 + 2.0f + float(0x1F) + -0.12");
		document.replace(0, text().length(), Text.of(odd));
		var session = begin("1. +");
		move(".5 +");
		move("1e3 +");
		move("2.0f +");
		move("0x1F");
		move("0.12");
		session.set("-0.345");
		move("1. +");
		session.set("1.25");
		session.end(true);
		String kept = odd.replace("-0.12", "-0.345").replace("1. +", "1.25 +");
		assertEquals(kept, text());
		document.undo();
		document.undo();
		assertEquals(odd, text());
		document.redo();
		document.redo();
		assertEquals(kept, text());
	}

	// The statement of the tuned number on one line: a call over two
	// lines joined, a for loop's header whole; its other numbers, and
	// which of them the session changed.
	@Test
	public void theStatementOfTheTunedNumber() {
		var session = begin("0.26,");
		var statement = session.statement();
		assertEquals("return smoothstep(", statement.before());
		assertEquals(", 0.01, length(p - vec2(0.5)));", statement.after());
		assertEquals(2, statement.marks().size());
		var second = statement.marks().get(0);
		assertEquals("0.01", statement.after().substring(second.start(),
				second.end()));
		assertFalse(second.changed());
		move("0.01,");
		session.set("0.02");
		move("0.26,");
		assertTrue(session.statement().marks().get(0).changed());
		move("3;");
		assertEquals("for (int i = 0; i < ", session.statement().before());
		assertEquals("; ++i) {", session.statement().after());
	}

	// Previous and next go through the numbers of the text in order,
	// past comments; at either end there is none.
	@Test
	public void previousAndNextWalkTheText() {
		document.replace(0, 0, Text.of("// 9.9 in a comment\n"));
		var session = begin("0.26,");
		assertNull(session.neighbour(false));
		assertEquals("0.01", session.neighbour(true));
		String[] order = {"0.01", "0.5", "0.0", "0", "3", "0.3", "2.0", "0.1",
				"0.2", "1.0", "0.25"};
		for (String number : order) {
			assertEquals(number, session.step(true).text());
		}
		assertNull(session.neighbour(true));
		assertNull(session.step(true));
		assertEquals("1.0", session.step(false).text());
	}

	// A number of a #define is one uniform where it is defined: every
	// use of the macro reads it; in a function-like macro too.
	@Test
	public void aDefineIsOneUniformForEveryUse() {
		String defined = SHADER.replace("uniform float time;\n",
				"uniform float time;\n#define GAIN 0.35\n" +
						"#define SAT(x) clamp(x, 0.0, 0.9)\n")
				.replace("gl_FragColor = vec4(s,",
						"gl_FragColor = vec4(SAT(s * GAIN), SAT(GAIN),");
		document.replace(0, text().length(), Text.of(defined));
		var session = begin("0.35");
		assertTrue(runs.get(0).text, runs.get(0).text.contains(
				"#define GAIN (se_tune0)\n"));
		session.set("0.5");
		move("0.9)");
		rest();
		var build = runs.get(runs.size() - 1);
		assertTrue(build.text, build.text.contains(
				"#define SAT(x) clamp(x, 0.0, (se_tune1))\n"));
		assertEquals("se_tune0=0.5 se_tune1=0.9", build.values());
		session.end(true);
		assertEquals(defined.replace("GAIN 0.35", "GAIN 0.5"), text());
	}

	// The period of ftime is read by the app, not compiled: a constant.
	@Test
	public void theFtimePeriodIsAConstant() {
		document.replace(0, text().length(), Text.of(lines(
				"#define FTIME_PERIOD 7.0", PRECISION,
				"uniform float ftime;",
				"void main(void) {",
				"	gl_FragColor = vec4(ftime);",
				"}")));
		begin("7.0");
		assertEquals(List.of("constant"), heard);
		assertEquals(List.of(), runs);
	}

	// Previous and next go through the numbers of #define lines too, and
	// past those of other directives.
	@Test
	public void previousAndNextGoThroughDefines() {
		document.replace(0, text().length(), Text.of(lines(
				"#define SPEED 0.35", "#define RADIUS 0.12",
				"#if 1", "#endif", PRECISION,
				"void main(void) {",
				"	gl_FragColor = vec4(SPEED, RADIUS, 0.5, 1.0);",
				"}")));
		var session = begin("0.35");
		assertEquals("0.12", session.step(true).text());
		assertEquals("#define RADIUS ", session.statement().before());
		assertEquals("", session.statement().after());
		assertEquals("0.5", session.step(true).text());
		assertEquals("0.12", session.step(false).text());
	}

	@Test
	public void oneSessionAtATime() {
		var first = begin("0.3");
		assertThrows(IllegalStateException.class, () -> begin("0.0"));
		first.end(false);
		var second = begin("0.0");
		assertEquals(second, tuner.open());
		second.end(false);
		assertNull(tuner.open());
	}

	// The screen goes: nothing is written or built, and what the session
	// waited for is not heard.
	@Test
	public void destroyLetsTheOpenSessionGo() {
		waits = true;
		var session = begin("0.3");
		tuner.destroy();
		settle();
		assertEquals(List.of(), runs);
		assertFalse(session.isOpen());
		assertNull(tuner.open());

		waits = false;
		var live = begin("0.3");
		var run = runs.get(0);
		live.set("0.5");
		tuner.destroy();
		applied = 0;
		run.heard.onLive();
		live.end(true);
		assertEquals(0, applied);
		assertEquals(1, runs.size());
		assertEquals(SHADER, text());
	}

	// The build of the session may begin to draw after end(): nothing
	// it tells is heard, and the text's build follows it.
	@Test
	public void aBuildThatDrawsAfterTheEndChangesNothing() {
		waits = true;
		var session = begin("0.3");
		settle();
		session.set("0.8");
		session.end(false);
		applied = 0;
		runs.get(0).heard.onLive();
		assertEquals(0, applied);
		settle();
		assertEquals(2, runs.size());
		assertEquals(SHADER, runs.get(1).text);
	}

	// What is set while the build is made is what it starts with.
	@Test
	public void aValueSetBeforeTheBuildIsItsFirst() {
		waits = true;
		var session = begin("0.3");
		session.set("0.9");
		settle();
		assertEquals(0.9, runs.get(0).tunings.get(0).value(), 0);
	}

	// A file the running shader does not read.
	@Test
	public void whatCannotBeTunedFailsAndEndsWithoutABuild() {
		files.put("main.frag", "");
		files.put("other.glsl", "float x = 2.0;\n");
		var other = new Document(Text.of("float x = 2.0;\n"));
		var place = TunedPlace.at("float x = 2.0;\n", 10);
		assertNotNull(place);
		var session = tuner.begin(other, path("other.glsl"), place,
				listener());
		assertEquals(List.of("failed [other.glsl:1: not part of what " +
				"runs]"), heard);
		assertFalse(session.isOpen());
		session.end(true);
		assertEquals(List.of(), runs);
	}

	// Review 3, M1: a value typed and not taken goes to the number it
	// was typed for before every end and move, whoever asks for it: the
	// toolbar's Save or Run end the session as Done does.
	@Test
	public void aTypedValueIsTakenBeforeEveryEndAndMove() {
		var session = begin("0.1,");
		typed = "0.15";
		move("0.2,");
		assertEquals(SHADER.replace("0.1, 0.2", "0.15, 0.2"), text());
		// Redo takes it too, and then has nothing to make again.
		typed = "0.25";
		session.redo();
		typed = "0.3";
		session.end(true);
		assertEquals(SHADER.replace("0.1, 0.2", "0.15, 0.3"), text());
	}

	// Revert takes it as the value tried: one Undo brings it back.
	@Test
	public void revertTakesATypedValueAsTheValueTried() {
		var session = begin("0.3 *");
		typed = "0.9";
		session.end(false);
		assertEquals(SHADER, text());
		document.undo();
		assertEquals(SHADER.replace("0.3 *", "0.9 *"), text());
	}

	// Review 3, M3: after Revert the picture has the text's values at
	// once, not once the text's build swaps in.
	@Test
	public void revertPutsTheUniformsBackAtOnce() {
		var session = begin("0.1,");
		live();
		session.set("0.65");
		move("0.2,");
		rest();
		live();
		session.set("0.45");
		session.end(false);
		assertEquals(SHADER, text());
		assertEquals(0.1, runs.get(0).tunings.get(0).value(), 0);
		assertEquals("se_tune0=0.1 se_tune1=0.2", runs.get(1).values());
	}

	// Review 3, M4: a write the document refuses on a move: the value
	// tried goes with its number, and no other number gets it.
	@Test
	public void aRefusedWriteOnMoveKeepsTheValueWithItsNumber() {
		String prefix = "void main(){gl_FragColor=vec4(1.,22222.,0.,1.);}";
		String full = prefix + "//" + "x".repeat((int) Document.MAX_BYTES -
				prefix.length() - 2);
		document.replace(0, text().length(), Text.of(full));
		// Past the source limit no build answers; none needs to.
		waits = true;
		var session = begin("1.,");
		session.set("1.25");
		var next = session.move(prefix.indexOf("22222."));
		assertNotNull(next);
		assertEquals("22222.", next.text());
		session.end(true);
		assertTrue(text().startsWith(prefix));
	}

	// Review 3, L2: a number refused alone and unchanged leaves the
	// other one waiting for its turn tried next.
	@Test
	public void anUnchangedRefusedNumberLetsTheNextOneBeTried() {
		var session = begin("0.1,");
		session.set("0.65");
		move("0.2,");
		rest();
		runs.get(1).heard.onRefused(List.of(ProjectDiagnostic.error("two")));
		rest();
		runs.get(2).heard.onRefused(List.of(ProjectDiagnostic.error("one")));
		rest();
		assertTrue(runs.size() > 3);
	}

	// Review 3, L4: a preparation that fails with nothing to blame is not
	// asked for again and again: the errors are told, and it ends.
	@Test
	public void aPersistentFailureIsToldNotRetried() {
		files.put("shared.glsl", "// valid\n");
		document.replace(0, text().length(), Text.of(SHADER.replace(
				"uniform float time;",
				"#include \"shared.glsl\"\nuniform float time;")));
		var session = begin("3;");
		session.set("5");
		rest();
		files.put("shared.glsl", "#include \"missing.glsl\"\n");
		session.set("6");
		rest();
		assertNull(resting);
		assertFalse(session.isOpen());
		assertTrue(heard.toString(), heard.get(heard.size() - 1)
				.startsWith("failed"));
	}

	// Review 3, L6: only the step changed: the same value in more
	// digits is no change and no step.
	@Test
	public void aStepOnlyChangeMakesNoStep() {
		var session = begin("0.3 *");
		live();
		session.set("0.34");
		move("2.0;");
		int steps = edits;
		move("0.34 *");
		session.set("0.340");
		move("2.0;");
		assertEquals(steps, edits);
		assertTrue(text(), text().contains("0.34 *"));
	}

	// Review 3, H1: the screen goes (a rotation) while a build is being
	// prepared; the project's late answer hands nothing to the worker
	// the screen shut down.
	@Test
	public void anAnswerAfterTheScreenWentDoesNotThrow() {
		waits = true;
		var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
		var screen = new Tuner(new Host(), Runnable::run, worker);
		files.putIfAbsent("main.frag", "");
		var session = screen.begin(document, path("main.frag"), place("0.3 *"),
				listener());
		session.end(true);
		screen.destroy();
		worker.shutdown();
		settle();
	}

	// Review 3, L9: the text is built at the end also when no build of
	// the session was handed over: the run of an edit made just before
	// the bar opened, which the bar held back, is not lost.
	@Test
	public void anEndBeforeTheBuildIsReadyBuildsTheText() {
		waits = true;
		document.replace(0, 0, Text.of("// typed just before\n"));
		var session = begin("0.3");
		session.end(false);
		settle();
		assertEquals(1, runs.size());
		assertEquals(text(), runs.get(0).text);
		assertFalse(runs.get(0).temporary);
	}

	@NonNull
	private TuningSession begin(@NonNull String number) {
		files.putIfAbsent("main.frag", "");
		return tuner.begin(document, path("main.frag"), place(number),
				listener());
	}

	@NonNull
	private TuningSession.Tuned move(@NonNull String number) {
		var session = tuner.open();
		assertNotNull(session);
		var tuned = session.move(text().indexOf(number));
		assertNotNull(number, tuned);
		return tuned;
	}

	@NonNull
	private TunedPlace place(@NonNull String number) {
		var place = TunedPlace.at(text(), text().indexOf(number));
		assertNotNull(number, place);
		return place;
	}

	private void live() {
		var heard = runs.get(runs.size() - 1).heard;
		assertNotNull(heard);
		heard.onLive();
	}

	// The finger rests.
	private void rest() {
		Runnable build = resting;
		resting = null;
		if (build != null) {
			build.run();
		}
	}

	private void settle() {
		while (!preparing.isEmpty()) {
			preparing.remove(0).run();
		}
	}

	@NonNull
	private String text() {
		return document.text().toString();
	}

	@NonNull
	private TuningSession.Listener listener() {
		return new TuningSession.Listener() {
			// As the bar: what was typed goes to the tuned number.
			@Override
			public void onSettle() {
				TuningSession open = tuner.open();
				if (typed != null && open != null) {
					String value = typed;
					typed = null;
					open.set(value);
				}
			}

			@Override
			public void onConstant(boolean constant) {
				if (constant) {
					heard.add("constant");
				}
			}

			@Override
			public void onBuilding(boolean building) {
			}

			@Override
			public void onTuned(@NonNull TuningSession.Tuned tuned) {
				heard.add("tuned " + tuned.text() + " from " + tuned.start());
			}

			@Override
			public void onFailed(@NonNull List<ProjectDiagnostic> errors) {
				heard.add("failed " + errors);
			}
		};
	}

	// The document and the other files, prepared as OpenProject does,
	// and an engine that runs what it gets.
	private class Host implements TuningSession.Host {
		@Override
		public Text text(@NonNull ProjectPath path) {
			return path.equals(path("main.frag"))
					? document.text()
					: files.containsKey(path.toString())
					? Text.of(files.get(path.toString()))
					: null;
		}

		@NonNull
		private String string(@NonNull ProjectPath path) {
			Text text = text(path);
			return text != null ? text.toString() : "";
		}

		@Override
		public void prepare(
				@NonNull List<OpenProject.Replacement> replacements,
				@NonNull FileTexts.Callback<Preparation> done) {
			deliver(replacements, false, done);
		}

		@Override
		public void prepare(@NonNull FileTexts.Callback<Preparation> done) {
			// As OpenProject.prepareAlways: delivered when the text
			// changed meanwhile too.
			deliver(List.of(), true, done);
		}

		@Override
		public void run(@NonNull Preparation preparation,
				@NonNull List<Tuning> tunings,
				@Nullable Tuning.Listener heard) {
			runs.add(new Run(new String(preparation.files().get(
					path("main.frag")).bytes(), StandardCharsets.UTF_8),
					preparation.temporary(), tunings, heard));
		}

		@Override
		public void applyTuning() {
			++applied;
		}

		@Override
		public void edit(@NonNull Runnable edit) {
			++edits;
			edit.run();
		}

		@Override
		public void rest(@NonNull Runnable build) {
			resting = build;
		}

		// As the editor keeps them: each line's state, from the first.
		@Override
		public int lineState(int line) {
			var text = document.text();
			var scratch = new LineTokens();
			int state = GlslLexer.TEXT_START;
			for (int n = 0; n < line; ++n) {
				state = GlslLexer.lexLine(text.line(n), true, state, scratch);
			}
			return state;
		}

		// As OpenProject: a preparation of a text that changed before it
		// is delivered is not delivered.
		private void deliver(@NonNull List<OpenProject.Replacement> parts,
				boolean always, @NonNull FileTexts.Callback<Preparation> done) {
			var texts = new HashMap<ProjectPath, ProjectFiles.Content>();
			for (var entry : files.entrySet()) {
				String text = string(path(entry.getKey()));
				var backwards = new ArrayList<>(parts);
				backwards.sort((a, b) -> b.start() - a.start());
				for (var part : backwards) {
					if (part.path().toString().equals(entry.getKey())) {
						text = text.substring(0, part.start()) + part.text() +
								text.substring(part.end());
					}
				}
				texts.put(path(entry.getKey()), new ProjectFiles.Content(
						text.getBytes(StandardCharsets.UTF_8), "r"));
			}
			var preparation = Preparation.prepare(texts::get,
					path("main.frag"), ProjectLimits.DEFAULT,
					BuiltinNames.ALL);
			Preparation prepared = parts.isEmpty()
					? preparation
					: preparation.asTemporary();
			if (!waits) {
				done.done(prepared);
				return;
			}
			int revision = document.revision();
			preparing.add(() -> {
				if (always || revision == document.revision()) {
					done.done(prepared);
				}
			});
		}
	}
}
