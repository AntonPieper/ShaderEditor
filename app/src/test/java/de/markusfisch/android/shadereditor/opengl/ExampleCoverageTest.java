package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.markusfisch.android.shadereditor.project.ExamplesTest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExampleCoverageTest {
	private static final Map<String, String> EXEMPT = new TreeMap<>();

	static {
		EXEMPT.put("gyroscope", "a direction that turns with the phone, (1, 1, 1) "
				+ "turned by the rotation since load, which no example uses yet");
		EXEMPT.put("inclinationMatrix", "only the rotation by inclination about "
				+ "the east axis; Compass shows the dip from inclination, and no "
				+ "picture drawn from the matrix can be checked by looking");
		EXEMPT.put("rotationVector", "the orientation as an axis times sin(angle / 2), "
				+ "not turned with the screen and from the game rotation vector, "
				+ "whose north is arbitrary; rotationMatrix already falls back to it");
		EXEMPT.put("runtime", "new from upstream; a wallpaper example follows separately");
	}

	private static final Pattern COMMENTS = Pattern.compile(
			"/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);
	private static final Pattern DECLARATION = Pattern.compile(
			"\\buniform\\s+(?:(?:lowp|mediump|highp)\\s+)?\\w+\\s+(\\w+)\\s*(?:\\[[^\\]]*\\]\\s*)?;");

	@Test
	public void everyBuiltinIsDeclaredByAnExample() throws IOException {
		var declared = declaredInExamples();
		var uncovered = new TreeSet<String>();
		var staleExemptions = new TreeSet<String>();
		for (var def : UniformTable.BUILTINS) {
			var name = def.getName();
			var covered = declared.contains(name);
			var exempt = EXEMPT.containsKey(name);
			if (!covered && !exempt) {
				uncovered.add(name);
			} else if (covered && exempt) {
				staleExemptions.add(name);
			}
		}
		assertTrue("Builtin uniforms declared by no example: " + uncovered
						+ ". Add an example or exempt them with a reason.",
				uncovered.isEmpty());
		assertTrue("Exempted uniforms are now declared by an example, "
						+ "remove them from EXEMPT: " + staleExemptions,
				staleExemptions.isEmpty());
	}

	private static Set<String> declaredInExamples() throws IOException {
		var files = new ArrayList<File>();
		collect(ExamplesTest.folder(), files);
		assertTrue("No examples in " + ExamplesTest.folder(), !files.isEmpty());
		var names = new TreeSet<String>();
		for (var file : files) {
			var source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
			Matcher m = DECLARATION.matcher(COMMENTS.matcher(source).replaceAll(" "));
			while (m.find()) {
				names.add(m.group(1));
			}
		}
		return names;
	}

	private static void collect(File dir, List<File> found) {
		var files = dir.listFiles();
		if (files == null) {
			return;
		}
		for (var file : files) {
			if (file.isDirectory()) {
				collect(file, found);
			} else if (file.getName().endsWith(".frag") || file.getName().endsWith(".glsl")) {
				found.add(file);
			}
		}
	}
}
