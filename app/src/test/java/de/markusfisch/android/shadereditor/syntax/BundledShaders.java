package de.markusfisch.android.shadereditor.syntax;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.TreeMap;

import de.markusfisch.android.shadereditor.project.ExamplesTest;

/**
 * The shaders in res/raw, by file name, and the examples, by their
 * path under examples/.
 */
public final class BundledShaders {
	private BundledShaders() {
	}

	public static Map<String, String> read() throws IOException {
		File[] files = findRawDirectory().listFiles((dir, name) ->
				name.endsWith(".glsl"));
		if (files == null || files.length == 0) {
			throw new AssertionError("No bundled shaders");
		}
		Map<String, String> shaders = new TreeMap<>();
		for (File file : files) {
			shaders.put(file.getName(), new String(
					Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
		}
		addExamples(ExamplesTest.folder(), "examples/", shaders);
		return shaders;
	}

	private static void addExamples(File dir, String prefix,
			Map<String, String> shaders) throws IOException {
		File[] files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (File file : files) {
			String name = prefix + file.getName();
			if (file.isDirectory()) {
				addExamples(file, name + "/", shaders);
			} else if (name.endsWith(".frag") || name.endsWith(".glsl")) {
				shaders.put(name, new String(
						Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
			}
		}
	}

	private static File findRawDirectory() {
		String relative = "src/main/res/raw";
		for (String base : new String[]{".", "app"}) {
			File dir = new File(base, relative);
			if (dir.isDirectory()) {
				return dir;
			}
		}
		throw new AssertionError("Cannot find " + relative + " from " +
				new File(".").getAbsolutePath());
	}
}
