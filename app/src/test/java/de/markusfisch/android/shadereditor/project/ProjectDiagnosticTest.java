package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ProjectDiagnosticTest {
	@Test
	public void showsWhatIsKnownOfTheLocation() {
		ProjectPath file = ProjectPath.parse("lib/noise.glsl").value();
		ProjectDiagnostic.Severity error = ProjectDiagnostic.Severity.ERROR;
		assertEquals("lib/noise.glsl:3: bad",
				new ProjectDiagnostic(error, "bad", file, 3).toString());
		assertEquals("lib/noise.glsl: bad",
				new ProjectDiagnostic(error, "bad", file, 0).toString());
		assertEquals("3: bad",
				new ProjectDiagnostic(error, "bad", null, 3).toString());
		assertEquals("bad", ProjectDiagnostic.error("bad").toString());
		assertEquals("bad", ProjectDiagnostic.error("bad").raw());
	}

	@Test
	public void onlyAnErrorIsAnError() {
		ProjectDiagnostic warning = new ProjectDiagnostic(
				ProjectDiagnostic.Severity.WARNING, "unused", null, 2);
		assertFalse(ProjectDiagnostic.anyError(List.of()));
		assertFalse(ProjectDiagnostic.anyError(List.of(warning, warning)));
		assertTrue(ProjectDiagnostic.anyError(List.of(warning,
				ProjectDiagnostic.error("bad"))));
	}
}
