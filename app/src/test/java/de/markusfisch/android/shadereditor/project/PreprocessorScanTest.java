package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class PreprocessorScanTest {
	private static final ProjectPath FILE =
			ProjectPath.parse("main.frag").value();

	@Test
	public void recognizesDirectivesWithWhitespaceAroundHash() {
		PreprocessorScan scan = scan(
				"#version 300 es",
				"  # extension GL_OES_EGL_image_external_essl3 : require",
				"\t#\tpragma se input \"a.png\" // trailing",
				"#include \"lib/a.glsl\"",
				"#define X 1");
		List<PreprocessorScan.Directive> directives = scan.directives();
		assertEquals(5, directives.size());
		assertEquals(PreprocessorScan.Kind.VERSION, directives.get(0).kind());
		assertEquals("300 es", directives.get(0).argument());
		assertEquals(PreprocessorScan.Kind.EXTENSION, directives.get(1).kind());
		assertEquals(PreprocessorScan.Kind.PRAGMA, directives.get(2).kind());
		assertEquals("se input \"a.png\"", directives.get(2).argument());
		assertEquals(3, directives.get(2).line());
		assertEquals(PreprocessorScan.Kind.INCLUDE, directives.get(3).kind());
		assertEquals("\"lib/a.glsl\"", directives.get(3).argument());
		assertEquals(PreprocessorScan.Kind.OTHER, directives.get(4).kind());
		assertEquals("define", directives.get(4).name());
	}

	@Test
	public void ignoresDirectivesInComments() {
		PreprocessorScan scan = scan(
				"// #include \"a.glsl\"",
				"/* #pragma se input \"a.png\"",
				"#include \"b.glsl\" */ float x;",
				"float y; /* c */ /*",
				"#version 300 es */");
		assertTrue(scan.directives().isEmpty());
		assertTrue(scan.diagnostics().isEmpty());
		assertEquals("                     float x;", scan.code().get(2));
	}

	@Test
	public void keepsCommentMarkersInsideQuotedPath() {
		PreprocessorScan scan = scan("#include \"a//b/*c.glsl\" // note");
		assertEquals("\"a//b/*c.glsl\"",
				scan.directives().get(0).argument());
		assertTrue(scan.diagnostics().isEmpty());
	}

	@Test
	public void tracksConditionalDepthAtEveryLine() {
		PreprocessorScan scan = scan(
				"float a;",
				"#if 0",
				"float b;",
				"#ifdef X",
				"float c;",
				"#elif Y",
				"#endif",
				"#else",
				"float d;",
				"#endif",
				"float e;");
		int[] expected = {0, 0, 1, 1, 2, 1, 1, 0, 1, 0, 0};
		for (int line = 1; line <= expected.length; ++line) {
			assertEquals("line " + line, expected[line - 1],
					scan.depthAt(line));
		}
		assertTrue(scan.diagnostics().isEmpty());
	}

	@Test
	public void reportsUnbalancedConditionals() {
		PreprocessorScan scan = scan("#endif", "#ifndef G", "#define G");
		assertEquals(2, scan.diagnostics().size());
		assertEquals(1, scan.diagnostics().get(0).line());
		assertEquals(2, scan.diagnostics().get(1).line());
		assertEquals(FILE, scan.diagnostics().get(1).file());
	}

	@Test
	public void reportsUnclosedBlockComment() {
		PreprocessorScan scan = scan("float a;", "/* open", "still open");
		assertEquals(1, scan.diagnostics().size());
		assertEquals(2, scan.diagnostics().get(0).line());
	}

	private static PreprocessorScan scan(String... lines) {
		return PreprocessorScan.scan(List.of(lines), FILE);
	}
}
