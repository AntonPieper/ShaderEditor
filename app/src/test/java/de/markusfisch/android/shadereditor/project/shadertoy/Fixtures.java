package de.markusfisch.android.shadereditor.project.shadertoy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shaders in ShaderToy's JSON, written for these tests: none is from
 * the site.
 */
public final class Fixtures {
	private Fixtures() {
	}

	/** The text of the fixture named name, such as "trail.json". */
	public static String text(String name) {
		try (InputStream in = Fixtures.class.getClassLoader()
				.getResourceAsStream("shadertoy/" + name)) {
			if (in == null) {
				throw new AssertionError("No fixture " + name);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new AssertionError(e);
		}
	}
}
