package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ExamplesTest {
	/** The folder of the bundled examples, as the app opens it. */
	public static File folder() {
		String relative = "src/debug/assets/examples";
		for (String base : new String[]{".", "app"}) {
			File dir = new File(base, relative);
			if (dir.isDirectory()) {
				return dir;
			}
		}
		throw new AssertionError("Cannot find " + relative + " from " +
				new File(".").getAbsolutePath());
	}

	/** Every file of the examples that runs, by its path. */
	public static List<ProjectPath> shaders() {
		List<ProjectPath> shaders = new ArrayList<>();
		collect(folder(), "", shaders);
		return shaders;
	}

	@Test
	public void everyExamplePreparesWithoutAWarning() {
		DirectoryProjectFiles files = new DirectoryProjectFiles(folder());
		List<ProjectPath> shaders = shaders();
		assertFalse(shaders.isEmpty());
		for (ProjectPath shader : shaders) {
			Preparation preparation = Preparation.prepare(files, shader,
					ProjectLimits.DEFAULT, BuiltinNames.ALL);
			assertEquals(shader.toString(), List.of(),
					preparation.diagnostics());
			assertNotNull(shader.toString(), preparation.plan());
		}
	}

	// A shader that runs is a .frag file that is not only a pass of
	// another: those lie in the folder of their running shader, which is
	// named main.frag.
	private static void collect(File dir, String prefix,
			List<ProjectPath> shaders) {
		File[] files = dir.listFiles();
		assertNotNull(files);
		boolean hasMain = new File(dir, "main.frag").isFile();
		for (File file : files) {
			String name = file.getName();
			if (file.isDirectory()) {
				collect(file, prefix + name + "/", shaders);
			} else if (name.endsWith(".frag") &&
					(!hasMain || name.equals("main.frag"))) {
				shaders.add(ProjectPath.parse(prefix + name).value());
			}
		}
	}
}
