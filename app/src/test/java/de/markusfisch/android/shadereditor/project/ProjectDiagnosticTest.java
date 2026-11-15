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

	// "Untitled 2.frag:1: syntax error" under a new Untitled 3.frag:
	// a report of the file that ran before is not told.
	@Test
	public void isAboutWhatIsOpenOnly() {
		ProjectPath shown = path("Untitled 3.frag");
		ProjectPath before = path("Untitled 2.frag");
		ProjectPath lib = path("lib/noise.glsl");
		ProjectDiagnostic.Severity error = ProjectDiagnostic.Severity.ERROR;
		ProjectDiagnostic ofBefore = new ProjectDiagnostic(error,
				"syntax error", before, 1);
		ProjectDiagnostic ofLib = new ProjectDiagnostic(error, "bad", lib, 3);
		ProjectDiagnostic ofShown = new ProjectDiagnostic(error, "bad",
				shown, 2);

		assertFalse(ofBefore.isAbout(shown, shown, lib::equals));
		assertTrue(ofShown.isAbout(shown, shown, lib::equals));
		assertTrue(ofLib.isAbout(shown, shown, lib::equals));
		// The shown file is read by another that runs.
		assertTrue(ofBefore.isAbout(lib, before, path -> false));
		assertTrue(ProjectDiagnostic.error("bad").isAbout(shown, shown,
				path -> false));
		// Nothing runs: what is told is of the manifest.
		assertTrue(ofBefore.isAbout(shown, null, path -> false));
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

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
