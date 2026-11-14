package de.markusfisch.android.shadereditor.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic.Severity;
import de.markusfisch.android.shadereditor.project.ProjectPath;

public class WallpaperDiagnosticsTest {
	// An error is why the wallpaper shows the last shader that worked,
	// or nothing: it comes first, with where it is.
	@Test
	public void errorsComeFirstWithTheirPlace() {
		var file = ProjectPath.parse("main.frag").value();
		assertEquals("main.frag:1: format=rgba16f needs a device\n" +
						"unused is unused",
				WallpaperDiagnostics.text(List.of(
						new ProjectDiagnostic(Severity.WARNING,
								"unused is unused", file, 3),
						new ProjectDiagnostic(Severity.ERROR,
								"format=rgba16f needs a device", file, 1))));
		assertEquals("", WallpaperDiagnostics.text(List.of()));
	}
}
