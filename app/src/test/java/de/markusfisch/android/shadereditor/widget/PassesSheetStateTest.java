package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;

public class PassesSheetStateTest {
	// A build that failed before anything ran: nothing runs, and the
	// sheet says how many errors are why; warnings are no reason.
	@Test
	public void countsTheErrorsOfABuildThatRunsNothing() {
		PassesSheet.State state = PassesSheet.State.CLOSED.withProblems(
				List.of(new ProjectDiagnostic(Severity.ERROR, "a", null, 1),
						new ProjectDiagnostic(Severity.WARNING, "b", null, 2),
						new ProjectDiagnostic(Severity.ERROR, "c", null, 3)));

		assertFalse(state.runs());
		assertEquals(2, state.errors());
		assertEquals(0, PassesSheet.State.CLOSED.errors());
	}
}
