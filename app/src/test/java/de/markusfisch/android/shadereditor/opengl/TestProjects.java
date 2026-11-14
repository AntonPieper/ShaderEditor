package de.markusfisch.android.shadereditor.opengl;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

import de.markusfisch.android.shadereditor.project.BuiltinNames;
import de.markusfisch.android.shadereditor.project.Pictures;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.RenderPlan;

// Projects in memory, prepared with main.frag as the running shader.
final class TestProjects {
	private TestProjects() {
	}

	// Takes paths and texts in turns; a file named .png is a picture,
	// which it is by its bytes, whatever text is given for it.
	static Preparation prepare(String... pathsAndTexts) {
		var files = new HashMap<ProjectPath, ProjectFiles.Content>();
		for (int i = 0; i < pathsAndTexts.length; i += 2) {
			files.put(path(pathsAndTexts[i]), new ProjectFiles.Content(
					pathsAndTexts[i].endsWith(".png")
							? Pictures.png()
							: pathsAndTexts[i + 1].getBytes(
									StandardCharsets.UTF_8),
					"r" + i));
		}
		return Preparation.prepare(files::get, path("main.frag"),
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
	}

	static RenderPlan plan(String... pathsAndTexts) {
		var preparation = prepare(pathsAndTexts);
		if (preparation.plan() == null) {
			throw new AssertionError(preparation.diagnostics().toString());
		}
		return preparation.plan();
	}

	static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}
}
