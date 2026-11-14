package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.path;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

public class PlanRuntimeTest {
	private static final String MAIN = "void main() {}";

	// The backbuffer is declared but never read, so linking removes it.
	@Test
	public void initialWithoutAReadBackbufferWarnsAtLink() {
		var preparation = prepare("main.frag", lines(
						"#pragma se target initial=\"seed.png\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.png", "png");
		assertEquals(List.of(), preparation.diagnostics());
		var warnings = checkInitial(preparation, false);
		assertEquals(1, warnings.size());
		assertEquals(ProjectDiagnostic.Severity.WARNING,
				warnings.get(0).severity());
		assertEquals(1, warnings.get(0).line());
		assertEquals(List.of(), checkInitial(preparation, true));
	}

	@Test
	public void initialWarnsOnceWhenPreparationWarnedAlready() {
		var preparation = prepare("main.frag", lines(
						"#pragma se target initial=\"seed.png\"",
						MAIN),
				"seed.png", "png");
		assertEquals(1, preparation.diagnostics().size());
		assertEquals(preparation.diagnostics(), checkInitial(preparation,
				false));
	}

	// Frame n draws into one texture of the target and reads the other,
	// which holds frame n - 1; the frame drawn is the one presented.
	@Test
	public void aPassReadsTheFrameItDrewBeforeAndPresentsTheNewOne() {
		var engine = new FakeEngine(prepare("main.frag", lines(
				"uniform sampler2D backbuffer;",
				"uniform vec2 resolution;",
				"void main() {",
				"	gl_FragColor = texture2D(backbuffer,",
				"			gl_FragCoord.xy / resolution);",
				"}")), 3);
		assertEquals(ShaderSwap.Allocation.MADE, engine.resize(8, 4));
		engine.draw(0);
		engine.draw(.1f);
		engine.draw(.2f);
		var draws = engine.draws("main.frag");
		int unit = engine.unit("main.frag", "backbuffer");
		assertEquals(3, draws.size());
		for (int i = 1; i < draws.size(); ++i) {
			assertEquals(draws.get(i - 1).target(),
					(int) draws.get(i).units().get(unit));
			assertNotEquals(draws.get(i - 1).target(), draws.get(i).target());
		}
		var presented = engine.presented();
		assertEquals(3, presented.size());
		for (int i = 0; i < draws.size(); ++i) {
			assertTrue(presented.get(i).units().containsValue(
					draws.get(i).target()));
		}
	}

	// In frame n, now reads what blur.frag drew in frame n and before
	// what it drew in frame n - 1.
	@Test
	public void aPassReadsTheCurrentAndThePreviousFrameOfAnother() {
		var engine = new FakeEngine(prepare("main.frag", lines(
						"#pragma se input \"blur.frag\"",
						"uniform sampler2D now;",
						"#pragma se input \"blur.frag\" frame=previous",
						"uniform sampler2D before;",
						MAIN),
				"blur.frag", MAIN), 3);
		engine.resize(8, 4);
		for (int i = 0; i < 3; ++i) {
			engine.draw(i);
		}
		var blur = engine.draws("blur.frag");
		var main = engine.draws("main.frag");
		int now = engine.unit("main.frag", "now");
		int before = engine.unit("main.frag", "before");
		for (int i = 0; i < 3; ++i) {
			assertEquals(blur.get(i).target(),
					(int) main.get(i).units().get(now));
			if (i > 0) {
				assertEquals(blur.get(i - 1).target(),
						(int) main.get(i).units().get(before));
			}
		}
	}

	// ftime is (time % FTIME_PERIOD) / FTIME_PERIOD * 2 - 1, each pass
	// with its own period.
	@Test
	public void eachPassHasItsOwnFtimePeriod() {
		var engine = new FakeEngine(prepare("main.frag", lines(
						"#pragma se input \"blur.frag\"",
						"uniform sampler2D blur;",
						"uniform float ftime;",
						MAIN),
				"blur.frag", lines(
						"#define FTIME_PERIOD 2.0",
						"uniform float ftime;",
						MAIN)), 3);
		engine.resize(8, 4);
		engine.draw(1.5f);
		assertEquals(.5f, engine.draws("blur.frag").get(0)
				.uniforms().get("ftime")[0], 1e-6f);
		assertEquals(0f, engine.draws("main.frag").get(0)
				.uniforms().get("ftime")[0], 1e-6f);
	}

	// a.frag and b.frag both start as seed.frag: one draw of it on reset,
	// stretched into both histories.
	@Test
	public void aSharedInitialShaderIsDrawnOncePerReset() {
		var engine = new FakeEngine(prepare("main.frag", lines(
						"#pragma se input \"a.frag\" frame=previous",
						"uniform sampler2D a;",
						"#pragma se input \"b.frag\" frame=previous",
						"uniform sampler2D b;",
						MAIN),
				"a.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"b.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						MAIN),
				"seed.frag", MAIN), 3);
		engine.resize(8, 4);
		engine.draw(0);
		var seed = engine.draws("seed.frag");
		assertEquals(1, seed.size());
		var stretched = new ArrayList<Integer>();
		for (var draw : engine.stretched()) {
			if (draw.units().get(engine.stretchUnit(draw)) ==
					seed.get(0).target()) {
				stretched.add(draw.target());
			}
		}
		assertEquals(2, stretched.size());
		assertNotEquals(stretched.get(0), stretched.get(1));
	}

	// On a reset, seed.frag reads data.frag as it starts: its current
	// and its previous frame are one texture then, which OpenGL ES 2.0
	// samples with one setting.
	@Test
	public void gles2CannotReadOnePassOnResetWithTwoSettings() {
		var preparation = prepare("main.frag", lines(
						"#pragma se target initial=\"seed.frag\"",
						"uniform sampler2D backbuffer;",
						MAIN),
				"seed.frag", lines(
						"#pragma se input \"data.frag\" filter=nearest",
						"uniform sampler2D a;",
						"#pragma se input \"data.frag\" frame=previous " +
								"filter=linear",
						"uniform sampler2D b;",
						MAIN),
				"data.frag", lines(
						"#pragma se target size=2x2",
						MAIN));
		var gles2 = new FakeEngine(preparation, 2);
		assertTrue(gles2.runtime.hasFailed());
		assertEquals(List.of(new ProjectDiagnostic(
						ProjectDiagnostic.Severity.ERROR,
						"a (seed.frag:1) and b read data.frag with different " +
								"settings when a reset draws seed.frag, where " +
								"the current and previous frame of data.frag " +
								"are one texture, which needs OpenGL ES 3.0; " +
								"this device only has OpenGL ES 2.0",
						path("seed.frag"), 3)),
				errors(gles2.diagnostics));
		assertFalse(new FakeEngine(preparation, 3).runtime.hasFailed());
	}

	private static List<ProjectDiagnostic> errors(
			List<ProjectDiagnostic> diagnostics) {
		var errors = new ArrayList<ProjectDiagnostic>();
		for (var diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				errors.add(diagnostic);
			}
		}
		return errors;
	}

	private static List<ProjectDiagnostic> checkInitial(
			Preparation preparation,
			boolean history) {
		var plan = preparation.plan();
		var warnings = new ArrayList<>(preparation.diagnostics());
		PlanRuntime.checkInitial(plan,
				PassSchedule.of(plan, new boolean[]{history}), warnings);
		return warnings;
	}
}
