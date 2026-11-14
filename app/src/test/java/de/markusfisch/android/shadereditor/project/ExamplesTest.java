package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

	@Test
	public void theIncludeLibraryIsPureAndUsed() throws IOException {
		File[] files = new File(folder(), "lib").listFiles();
		assertNotNull(files);
		StringBuilder examples = new StringBuilder();
		for (ProjectPath shader : shaders()) {
			examples.append(text(new File(folder(), shader.toString())));
		}
		for (File file : files) {
			// Library functions may call each other.
			examples.append(text(file).replaceAll(
					"(?m)^\\w+\\s+\\w+\\s*\\(", ""));
		}
		Pattern function = Pattern.compile(
				"^\\w+\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);
		Pattern impure = Pattern.compile(
				"\\buniform\\b|\\bprecision\\b|#\\s*version|#\\s*pragma|" +
						"\\btexture(2D|Cube)?\\s*\\(");
		for (File file : files) {
			String text = text(file);
			String code = text.replaceAll("//[^\\n]*", "");
			assertFalse(file.getName(), impure.matcher(code).find());
			assertTrue(file.getName(), code.trim().startsWith("#ifndef"));
			Matcher m = function.matcher(code);
			while (m.find()) {
				assertTrue(m.group(1) + " of " + file.getName() +
								" is called by no example",
						Pattern.compile("\\b" + m.group(1) + "\\s*\\(")
								.matcher(examples).find());
			}
		}
	}

	private static String text(File file) throws IOException {
		return new String(Files.readAllBytes(file.toPath()),
				StandardCharsets.UTF_8);
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
