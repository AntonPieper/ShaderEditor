package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

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
}
