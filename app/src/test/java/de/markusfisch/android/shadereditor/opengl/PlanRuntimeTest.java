package de.markusfisch.android.shadereditor.opengl;

import static de.markusfisch.android.shadereditor.opengl.TestProjects.lines;
import static de.markusfisch.android.shadereditor.opengl.TestProjects.prepare;
import static org.junit.Assert.assertEquals;

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
