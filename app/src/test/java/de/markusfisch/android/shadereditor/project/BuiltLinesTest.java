package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.editor.DimmedLines;
import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

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
		assertEquals(List.of(8), built.off(MAIN, 3));
		// The guard is off the second time; nothing of a file that is
		// included is told.
		assertEquals(List.of(), built.off(LIB, 3));
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
		assertEquals(List.of(7), built.off(MAIN, 3));
		assertEquals(List.of(4), built.off(ProjectPath.parse("a.frag")
				.value(), 3));
	}

	private static final String SWITCHED = """
			#version 300 es
			precision highp float;
			#include "lib.glsl"
			#define AA 1
			out vec4 color;
			#if AA > 1
			float a = 1.0;
			#else
			float a = 2.0;
			#endif
			#if QUALITY > 1
			float q = 1.0;
			#else
			float q = 0.0;
			#endif
			void main() { color = vec4(a + q); }
			""";

	// Whether the dimmed lines asked for their directives to run again.
	private boolean asked;

	@Test
	public void aKeyOnADefineFlipsItsBlockOnce() {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("lib.glsl", "#define QUALITY 2\n");
		files.put("main.frag", SWITCHED);
		Preparation preparation = prepared(files);
		BuiltLines.Again again = BuiltLines.again(preparation, MAIN,
				BuiltinNames.ALL, MAIN, 3);
		Document document = new Document(Text.of(SWITCHED));
		DimmedLines dimmed = new DimmedLines(document, () -> asked = true);
		assertTrue(dimmed.set(document.text(),
				BuiltLines.of(preparation.plan()).off(MAIN, 3)));
		assertEquals("______X______X___", marked(dimmed, document));
		// What the build read is read from its copy, not from storage.
		files.delete(LIB);
		int at = SWITCHED.indexOf("AA 1") + 3;
		document.replace(at, at + 1, Text.of("2"));
		// Till the answer, all is as the build left it.
		assertTrue(asked);
		assertEquals("______X______X___", marked(dimmed, document));
		List<Integer> off = again.off(document.text().toString());
		assertTrue(dimmed.answer(document.text(), off));
		assertEquals("________X____X___", marked(dimmed, document));
		files.put("lib.glsl", "#define QUALITY 2\n");
		files.put("main.frag", document.text().toString());
		assertEquals(BuiltLines.of(prepared(files).plan()).off(MAIN, 3), off);
	}

	@Test
	public void anEditAboveEverythingFlipsNothing() {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("lib.glsl", "#define QUALITY 2\n");
		files.put("main.frag", SWITCHED);
		Preparation preparation = prepared(files);
		Document document = new Document(Text.of(SWITCHED));
		DimmedLines dimmed = new DimmedLines(document, () -> asked = true);
		dimmed.set(document.text(),
				BuiltLines.of(preparation.plan()).off(MAIN, 3));
		document.replace(0, 0, Text.of("// note\n"));
		assertTrue(asked);
		assertEquals("_______X______X___", marked(dimmed, document));
		assertTrue(dimmed.answer(document.text(), BuiltLines.again(preparation,
				MAIN, BuiltinNames.ALL, MAIN, 3).off(document.text()
				.toString())));
		assertEquals("_______X______X___", marked(dimmed, document));
	}

	@Test
	public void anIncludedFileIsNeverDimmedAgain() {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("lib.glsl", "#define QUALITY 2\n");
		files.put("main.frag", SWITCHED);
		BuiltLines.Again again = BuiltLines.again(prepared(files), MAIN,
				BuiltinNames.ALL, LIB, 3);
		assertEquals(List.of(), again.off("#if 0\nx\n#endif\n"));
		// Nor a text that does not prepare.
		assertFalse(BuiltLines.again(prepared(files), MAIN, BuiltinNames.ALL,
				MAIN, 3).off(SWITCHED.replace("#endif", "")).iterator()
				.hasNext());
	}

	// OpenGL ES 2.0 refuses a program of GLSL ES 3.00 before compiling
	// it: it dims no line, on its own or again after a key; a program of
	// ES 1.00 beside it does.
	@Test
	public void aProgramTheEngineRefusesDimsNothing() {
		MemoryProjectFiles files = new MemoryProjectFiles();
		String pass = "#version 300 es\nprecision highp float;\n" +
				"out vec4 color;\n#define V 0.5\n#if 0\nx\n#endif\n" +
				"void main() { color = vec4(V); }\n";
		files.put("a.frag", pass);
		files.put("main.frag", "precision highp float;\n" +
				"#pragma se input \"a.frag\"\nuniform sampler2D a;\n" +
				"#if 0\ny\n#endif\n" +
				"void main() { gl_FragColor = texture2D(a, vec2(0.0)); }\n");
		ProjectPath a = ProjectPath.parse("a.frag").value();
		Preparation preparation = prepared(files);
		BuiltLines built = BuiltLines.of(preparation.plan());
		assertEquals(List.of(5), built.off(a, 3));
		assertEquals(List.of(), built.off(a, 2));
		assertEquals(List.of(4), built.off(MAIN, 2));
		String edited = pass.replace("#if 0", "#if 1 - 1");
		assertEquals(List.of(), BuiltLines.again(preparation, MAIN,
				BuiltinNames.ALL, a, 2).off(edited));
		assertEquals(List.of(5), BuiltLines.again(preparation, MAIN,
				BuiltinNames.ALL, a, 3).off(edited));
	}

	private static Preparation prepared(MemoryProjectFiles files) {
		Preparation preparation = Preparation.prepare(files, MAIN,
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertEquals(List.of(), preparation.diagnostics());
		return preparation;
	}

	private static String marked(DimmedLines dimmed, Document document) {
		StringBuilder sb = new StringBuilder();
		for (int n = 0; n < document.text().lineCount(); ++n) {
			sb.append(dimmed.isOff(n) ? 'X' : '_');
		}
		return sb.toString();
	}

	// Includes that add lines, an include in an include and one in an off
	// arm: the grey lines are the pass's own, the #include in the off arm
	// is none, as no line of the text is its own.
	@Test
	public void mapsTheLinesBackThroughIncludes() {
		MemoryProjectFiles files = new MemoryProjectFiles();
		files.put("lib.glsl", "float l1;\nfloat l2;\n#if 0\nfloat l4;\n" +
				"#endif\nfloat l6;\n");
		files.put("deep.glsl", "#include \"lib.glsl\"\nfloat d2;\n");
		files.put("main.frag", """
				#version 300 es
				precision mediump float;
				#include "deep.glsl"
				#if 0
				float m5;
				#else
				float m7;
				#endif
				#include "lib.glsl"
				#ifndef SHADER_EDITOR
				float m11;
				#endif
				#if 0
				#include "lib.glsl"
				float m15;
				#endif
				out vec4 o;
				void main() { o = vec4(0.0); }
				""");
		assertEquals(List.of(4, 10, 14), built(files).off(MAIN, 3));
	}

	private static BuiltLines built(MemoryProjectFiles files) {
		Preparation preparation = Preparation.prepare(files, MAIN,
				ProjectLimits.DEFAULT, BuiltinNames.ALL);
		assertEquals(List.of(), preparation.diagnostics());
		return BuiltLines.of(preparation.plan());
	}
}
