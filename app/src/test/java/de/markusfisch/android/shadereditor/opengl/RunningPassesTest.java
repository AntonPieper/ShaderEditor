package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.opengl.RunningPasses.Kind;
import de.markusfisch.android.shadereditor.opengl.RunningPasses.Read;
import de.markusfisch.android.shadereditor.opengl.RunningPasses.ReadKind;
import de.markusfisch.android.shadereditor.project.BuiltinNames;
import de.markusfisch.android.shadereditor.project.DirectoryProjectFiles;
import de.markusfisch.android.shadereditor.project.ExamplesTest;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;

// The passes as the sheet's rail draws them, from the build that runs:
// in drawing order, first frames above the pass they seed, and what
// each reads.
public class RunningPassesTest {
	private static final String MAIN = lines(
			"#ifdef GL_FRAGMENT_PRECISION_HIGH",
			"precision highp float;",
			"#else",
			"precision mediump float;",
			"#endif",
			"void main(void) {",
			"	gl_FragColor = vec4(1.0);",
			"}");

	// Ink: ink.frag reads itself and starts as seed.frag's one frame;
	// main.frag reads ink.frag and a picture.
	@Test
	public void ink() {
		var rows = example("Ink/main.frag").rows();
		assertEquals(List.of("Ink/seed.frag", "Ink/ink.frag",
				"Ink/main.frag"), files(rows));
		var seed = rows.get(0);
		assertEquals(Kind.FIRST_FRAME, seed.kind());
		assertTrue(seed.drawn());
		var ink = rows.get(1);
		assertEquals(Kind.PASS, ink.kind());
		assertEquals(List.of(new Read(1, ReadKind.LAST_FRAME),
				new Read(0, ReadKind.FIRST_FRAME)), ink.reads());
		var main = rows.get(2);
		assertTrue(main.output());
		assertFalse(ink.output());
		assertEquals(List.of(new Read(1, ReadKind.THIS_FRAME)), main.reads());
		assertEquals(List.of(path("textures/noise.png")), main.pictures());
	}

	// A chain with a fan-out: scene.frag is read by bright.frag and by
	// main.frag.
	@Test
	public void bloom() {
		var rows = example("Bloom/main.frag").rows();
		assertEquals(List.of("Bloom/scene.frag", "Bloom/bright.frag",
				"Bloom/blur.frag", "Bloom/main.frag"), files(rows));
		for (var row : rows) {
			assertEquals(Kind.PASS, row.kind());
			assertTrue(row.drawn());
			for (var read : row.reads()) {
				assertEquals(ReadKind.THIS_FRAME, read.kind());
			}
		}
		assertEquals(List.of(0), producers(rows.get(1)));
		assertEquals(List.of(1), producers(rows.get(2)));
		assertTrue(producers(rows.get(3)).containsAll(List.of(0, 2)));
	}

	@Test
	public void gameOfLife() {
		var rows = example("Game of Life/main.frag").rows();
		assertEquals(List.of("Game of Life/seed.frag",
				"Game of Life/life.frag", "Game of Life/main.frag"),
				files(rows));
		assertEquals(List.of(new Read(1, ReadKind.LAST_FRAME),
				new Read(0, ReadKind.FIRST_FRAME)), rows.get(1).reads());
	}

	// An unannotated backbuffer is the pass reading itself.
	@Test
	public void trailsReadsItselfThroughItsBackbuffer() {
		var rows = example("Trails.frag").rows();
		assertEquals(1, rows.size());
		assertEquals(List.of(new Read(0, ReadKind.LAST_FRAME)),
				rows.get(0).reads());
	}

	// A first frame whose pass reads no last frame has no effect: it is
	// listed above the pass, not drawn, with the build's warning.
	@Test
	public void aFirstFrameNothingReadsIsNotDrawn() {
		var engine = new FakeEngine(prepare(
				"main.frag", lines(
						"#pragma se input \"a.frag\"",
						"uniform sampler2D a;",
						MAIN),
				"a.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"seed.frag", MAIN), 3);
		var rows = engine.runtime.running().rows();
		assertEquals(List.of("seed.frag", "a.frag", "main.frag"), files(rows));
		assertFalse(rows.get(0).drawn());
		assertEquals(List.of(), rows.get(1).reads());
		assertEquals(1, rows.get(1).problems().size());
		assertTrue(rows.get(1).problems().get(0).message()
				.startsWith("initial has no effect"));
		assertNull(rows.get(1).startsFrom());
		assertEquals(2, engine.runtime.running().indexOf(path("main.frag")));
	}

	@NonNull
	private static RunningPasses example(@NonNull String shader) {
		Preparation preparation = Preparation.prepare(
				new DirectoryProjectFiles(ExamplesTest.folder()),
				path(shader), ProjectLimits.DEFAULT, BuiltinNames.ALL);
		// Linked only: Ink's sensors do not start on the JVM.
		var errors = new ArrayList<ProjectDiagnostic>();
		var runtime = PlanRuntime.link(new FakeGlDevice(),
				new PlanShader(preparation, 1f, "fix"),
				TargetFormats.of(3, ""), errors);
		assertTrue(errors.toString(), runtime != null);
		return runtime.running();
	}

	@NonNull
	private static List<String> files(@NonNull List<RunningPasses.Row> rows) {
		var files = new ArrayList<String>();
		for (var row : rows) {
			files.add(row.file().toString());
		}
		return files;
	}

	@NonNull
	private static List<Integer> producers(@NonNull RunningPasses.Row row) {
		var producers = new ArrayList<Integer>();
		for (var read : row.reads()) {
			producers.add(read.row());
		}
		return producers;
	}
}
