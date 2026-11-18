package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

public class BuiltLinesTest {
	private static final ProjectPath MAIN = ProjectPath.parse("main.frag")
			.value();
	private static final ProjectPath LIB = ProjectPath.parse("lib.glsl")
			.value();

	@Test
	public void dimsWhatTheIncludesSwitchOff() {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("lib.glsl", "#ifndef LIB\n#define LIB\n#define AA 2\n" +
				"#endif\n");
		files.put("main.frag", "#version 300 es\n" +
				"precision highp float;\n" +
				"#include \"lib.glsl\"\n" +
				"#include \"lib.glsl\"\n" +
				"out vec4 color;\n" +
				"#if AA > 1\n" +
				"#define S(x) (x * 0.5)\n" +
				"#else\n" +
				"#define S(x) (x)\n" +
				"#endif\n" +
				"void main() { color = vec4(S(1.0)); }\n");
		BuiltLines built = built(files);
		assertEquals(List.of(8), built.off(MAIN));
		// The guard is off the second time; nothing of a file that is
		// included is told.
		assertEquals(List.of(), built.off(LIB));
		assertNull(built.expansion(LIB, 2, 12, "AA"));
		assertEquals("(1.0 * 0.5)", built.expansion(MAIN, 10, 27, "S"));
		assertNull(built.expansion(MAIN, 10, 14, "color"));
	}

	@Test
	public void dimsLinesOfEveryPassThatRuns() {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("a.frag", "#version 300 es\nprecision highp float;\n" +
				"out vec4 color;\n#if 0\nx\n#endif\n" +
				"void main() { color = vec4(1.0); }\n");
		files.put("main.frag", "#version 300 es\nprecision highp float;\n" +
				"#pragma se input \"a.frag\"\n" +
				"uniform sampler2D a;\nout vec4 color;\n" +
				"#ifdef SHADER_EDITOR\n#else\nnot\n#endif\n" +
				"void main() { color = texture(a, vec2(0.0)); }\n");
		BuiltLines built = built(files);
		assertEquals(List.of(7), built.off(MAIN));
		assertEquals(List.of(4), built.off(ProjectPath.parse("a.frag")
				.value()));
	}

	private static BuiltLines built(MemoryProjectFiles files) {
		Preparation preparation = Preparation.prepare(files, MAIN,
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertEquals(List.of(), preparation.diagnostics());
		return BuiltLines.of(preparation.plan());
	}
}
