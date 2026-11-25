package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class BuiltTreesTest {
	private static final String MAIN = "#version 300 es\n" +
			"precision highp float;\n" +
			"#include \"lib/shapes.glsl\"\n" +
			"uniform vec2 resolution;\n" +
			"out vec4 color;\n" +
			"#define TWICE(v) (v + v)\n" +
			"#ifdef SOFT\n" +
			"float extra = 1.0;\n" +
			"#endif\n" +
			"void main() {\n" +
			"\tfloat d = circle(gl_FragCoord.xy / resolution, radius);\n" +
			"\tcolor = vec4(TWICE(d));\n" +
			"}\n";
	private static final String SHAPES = "const float radius = 0.5;\n" +
			"float circle(vec2 p, float r) {\n" +
			"\treturn length(p) - r;\n" +
			"}\n";

	@Test
	public void aNameMeansItsDeclarationInWhateverFileThatIs() {
		BuiltTrees trees = build(MAIN, SHAPES, null);
		assertEquals("lib/shapes.glsl:1:6-12", at(trees, "main.frag", MAIN, "circle("));
		assertEquals("lib/shapes.glsl:0:12-18", at(trees, "main.frag", MAIN, "radius)"));
		assertEquals("main.frag:3:13-23", at(trees, "main.frag", MAIN, "resolution,"));
		assertEquals("main.frag:10:7-8", at(trees, "main.frag", MAIN, "d));"));
		assertEquals("main.frag:4:9-14", at(trees, "main.frag", MAIN, "color ="));
		// In the included file, as the program that includes it reads it.
		assertEquals("lib/shapes.glsl:1:27-28",
				at(trees, "lib/shapes.glsl", SHAPES, "r;"));
		assertEquals("lib/shapes.glsl:1:18-19",
				at(trees, "lib/shapes.glsl", SHAPES, "p) -"));
		// A declaration is its own.
		assertEquals("lib/shapes.glsl:1:6-12",
				at(trees, "lib/shapes.glsl", SHAPES, "circle("));
	}

	@Test
	public void aMacrosNameMeansItsDefine() {
		BuiltTrees trees = build(MAIN, SHAPES, null);
		assertEquals("main.frag:5:8-13", at(trees, "main.frag", MAIN, "TWICE(d)"));
		assertEquals("main.frag:5:8-13", at(trees, "main.frag", MAIN, "TWICE(v)"));
	}

	@Test
	public void withoutAPlaceItSaysWhy() {
		BuiltTrees trees = build(MAIN, SHAPES, null);
		assertEquals("LANGUAGE", at(trees, "main.frag", MAIN, "gl_FragCoord"));
		assertEquals("LANGUAGE", at(trees, "main.frag", MAIN, "vec4(TWICE"));
		assertEquals("LANGUAGE", at(trees, "lib/shapes.glsl", SHAPES, "length("));
		assertEquals("NO_NAME", at(trees, "main.frag", MAIN, "= circle"));
		assertEquals("NOT_COMPILED", at(trees, "main.frag", MAIN, "extra"));
		assertEquals("NOT_COMPILED", at(trees, "other.glsl", "float a;\n", "a;"));
		assertEquals("NOT_DECLARED", at(build(MAIN.replace("radius)", "radios)"),
				SHAPES, null), "main.frag", MAIN, "radius)"));
		// With an error in the text it may only not have been read.
		String broken = MAIN.replace("float d =", "float d = = (");
		assertEquals("BROKEN", at(build(broken.replace("vec4(TWICE", "vec4(zz + TWICE"),
				SHAPES, null), "main.frag",
				broken.replace("vec4(TWICE", "vec4(zz + TWICE"), "zz +"));
	}

	@Test
	public void aNameInAMacrosTextMeansWhatItMeansWhereItIsInvoked() {
		String main = "#version 300 es\nprecision highp float;\nout vec4 color;\n" +
				"#define SCALED (x * scale)\nconst float scale = 2.0;\n" +
				"float a(float x) { return SCALED; }\n" +
				"float b() { float x = 1.0; return SCALED; }\n" +
				"void main() { color = vec4(a(1.0) + b()); }\n";
		BuiltTrees trees = build(main, SHAPES, null);
		// One meaning for all uses, and two.
		assertEquals("main.frag:4:12-17", at(trees, "main.frag", main, "scale)"));
		BuiltTrees.Definition x = trees.definition(path("main.frag"), 3, 16);
		assertEquals(List.of(new BuiltTrees.Place(path("main.frag"), 5, 14, 15),
				new BuiltTrees.Place(path("main.frag"), 6, 18, 19)), x.places());
		assertNull(x.why());
	}

	@Test
	public void theTreesOfAnEarlierBuildAreReadAgain() {
		BuiltTrees before = build(MAIN, SHAPES, null);
		String edited = MAIN.replace("float d =", "float e = 2.0;\n\tfloat d = e *");
		BuiltTrees trees = build(edited, SHAPES, before);
		assertEquals("main.frag:10:7-8", at(trees, "main.frag", edited, "e *"));
		assertEquals("main.frag:11:7-8", at(trees, "main.frag", edited, "d));"));
		assertEquals("lib/shapes.glsl:1:6-12",
				at(trees, "main.frag", edited, "circle("));
		// A condition only the driver decides makes the answer a guess.
		String guess = MAIN.replace("#ifdef SOFT", "#ifdef GL_SOFT");
		BuiltTrees.Definition extra = build(guess, SHAPES, trees).definition(
				path("main.frag"), 7, 6);
		assertTrue(extra.guess());
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}

	private static BuiltTrees build(String main, String shapes,
			BuiltTrees before) {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("main.frag", main);
		files.put("lib/shapes.glsl", shapes);
		return BuiltTrees.of(Preparation.prepare(files, path("main.frag"),
				ProjectLimits.DEFAULT, BuiltinNames.ALL).plan(), before);
	}

	// What the name at the first place of find in text means: its
	// place as file:line:start-end, places joined by a space, or why
	// it has none.
	private static String at(BuiltTrees trees, String file, String text,
			String find) {
		int offset = text.indexOf(find);
		int line = 0;
		int lineStart = 0;
		for (int i = 0; i < offset; ++i) {
			if (text.charAt(i) == '\n') {
				++line;
				lineStart = i + 1;
			}
		}
		BuiltTrees.Definition definition = trees.definition(path(file), line,
				offset - lineStart);
		StringBuilder sb = new StringBuilder();
		for (BuiltTrees.Place place : definition.places()) {
			sb.append(sb.length() > 0 ? " " : "").append(place.file()).append(':')
					.append(place.line()).append(':').append(place.start())
					.append('-').append(place.end());
		}
		return sb.length() > 0 ? sb.toString() : String.valueOf(definition.why());
	}
}
