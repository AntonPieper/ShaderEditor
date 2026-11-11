package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ExampleCoverageTest {
	private static final String NO_EXAMPLE = "no example yet";

	private static final Map<String, String> EXEMPT = new TreeMap<>();

	static {
		EXEMPT.put("cameraFront", NO_EXAMPLE);
		EXEMPT.put("date", NO_EXAMPLE);
		EXEMPT.put("daytime", NO_EXAMPLE);
		EXEMPT.put("frame", NO_EXAMPLE);
		EXEMPT.put("ftime", NO_EXAMPLE);
		EXEMPT.put("gyroscope", NO_EXAMPLE);
		EXEMPT.put("inclination", NO_EXAMPLE);
		EXEMPT.put("inclinationMatrix", NO_EXAMPLE);
		EXEMPT.put("lastNotificationTime", NO_EXAMPLE);
		EXEMPT.put("light", NO_EXAMPLE);
		EXEMPT.put("linear", NO_EXAMPLE);
		EXEMPT.put("magnetic", NO_EXAMPLE);
		EXEMPT.put("mediaVolume", NO_EXAMPLE);
		EXEMPT.put("micAmplitude", NO_EXAMPLE);
		EXEMPT.put("mouse", NO_EXAMPLE);
		EXEMPT.put("nightMode", NO_EXAMPLE);
		EXEMPT.put("notificationCount", NO_EXAMPLE);
		EXEMPT.put("offset", NO_EXAMPLE);
		EXEMPT.put("powerConnected", NO_EXAMPLE);
		EXEMPT.put("pressure", NO_EXAMPLE);
		EXEMPT.put("proximity", NO_EXAMPLE);
		EXEMPT.put("rotationMatrix", NO_EXAMPLE);
		EXEMPT.put("rotationVector", NO_EXAMPLE);
		EXEMPT.put("second", NO_EXAMPLE);
		EXEMPT.put("startRandom", NO_EXAMPLE);
		EXEMPT.put("subsecond", NO_EXAMPLE);
		EXEMPT.put("touch", NO_EXAMPLE);
		EXEMPT.put("touchStart", NO_EXAMPLE);
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
		assertTrue("Builtin uniforms declared by no sample_*.glsl: " + uncovered
						+ ". Add an example or exempt them with a reason.",
				uncovered.isEmpty());
		assertTrue("Exempted uniforms are now declared by an example, "
						+ "remove them from EXEMPT: " + staleExemptions,
				staleExemptions.isEmpty());
	}

	private static Set<String> declaredInExamples() throws IOException {
		var dir = findRawDirectory();
		var files = dir.listFiles((d, name) ->
				name.startsWith("sample_") && name.endsWith(".glsl"));
		assertTrue("No sample_*.glsl files in " + dir, files != null && files.length > 0);
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

	private static File findRawDirectory() {
		var relative = "src/main/res/raw";
		for (var base : new String[]{".", "app"}) {
			var dir = new File(base, relative);
			if (dir.isDirectory()) {
				return dir;
			}
		}
		throw new AssertionError("Cannot find " + relative + " from working directory "
				+ new File(".").getAbsolutePath());
	}
}
