package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;
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

	private static List<ProjectDiagnostic> checkInitial(
			Preparation preparation,
			boolean history) {
		var plan = preparation.plan();
		var warnings = new ArrayList<>(preparation.diagnostics());
		PlanRuntime.checkInitial(plan, plan.passes().get(plan.output()),
				history, warnings);
		return warnings;
	}
}
