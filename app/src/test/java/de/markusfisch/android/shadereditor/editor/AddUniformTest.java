package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class AddUniformTest {
	@Test
	public void goesBehindTheLastUniformLine() {
		assertEquals("uniform float a;\nuniform float time;\nvoid main(){}",
				add("uniform float a;\nvoid main(){}", "uniform float time;"));
	}

	@Test
	public void replacesTheSameUniform() {
		// As the old editor: only a line followed by an empty line or
		// the end of the text.
		assertEquals("uniform float a;\nuniform vec2 resolution;\n",
				add("uniform float a;\nuniform vec2  resolution;\n",
						"uniform vec2 resolution;"));
	}

	@Test
	public void goesBehindTheLastEndifWithAnEmptyLine() {
		assertEquals("#ifdef A\n#endif\n\nuniform float time;\nvoid main(){}",
				add("#ifdef A\n#endif\nvoid main(){}", "uniform float time;"));
	}

	@Test
	public void goesToTheTopOtherwise() {
		assertEquals("uniform float time;\nvoid main(){}",
				add("void main(){}", "uniform float time;"));
	}

	@Test
	public void addsSeveralInOrderAsOneUndoStep() {
		String text = "#version 300 es\nprecision highp float;\n" +
				"void main(){}";
		Document document = new Document(Text.of(text));
		int[] at = AddUniform.apply(document, List.of(
				"uniform mat2 cameraOrientation;",
				"uniform vec2 cameraAddent;"));
		String added = document.text().toString();
		assertEquals("#version 300 es\nprecision highp float;\n" +
				"uniform mat2 cameraOrientation;\n" +
				"uniform vec2 cameraAddent;\n" +
				"void main(){}", added);
		assertTrue(added.startsWith("uniform mat2 cameraOrientation;",
				at[0]));
		assertTrue(added.startsWith("uniform vec2 cameraAddent;", at[1]));
		document.undo();
		assertEquals(text, document.text().toString());
	}

	@Test
	public void keepsWhereEarlierOnesStartWhenALaterOneTakesOutALine() {
		// The second takes out a line above the first one.
		Document document = new Document(Text.of(
				"uniform vec2  resolution;\nuniform float a;\n"));
		int[] at = AddUniform.apply(document, List.of(
				"uniform float time;", "uniform vec2 resolution;"));
		String added = document.text().toString();
		assertTrue(added.startsWith("uniform float time;", at[0]));
		assertTrue(added.startsWith("uniform vec2 resolution;", at[1]));
	}

	@Test
	public void isOneUndoStep() {
		Document document = new Document(Text.of(
				"uniform float a;\nuniform float time;\n"));
		AddUniform.apply(document, "uniform float time;");
		document.undo();
		assertEquals("uniform float a;\nuniform float time;\n",
				document.text().toString());
	}

	@Test
	public void returnsWhereTheStatementStarts() {
		Document document = new Document(Text.of(
				"#version 300 es\nprecision highp float;\nvoid main(){}"));
		int at = AddUniform.apply(document, "uniform float time;");
		assertEquals("uniform float time;", document.text().substring(at,
				at + "uniform float time;".length()));
	}

	// GLSL ES 3.00: #version must come first. A shader without a
	// uniform line gets the statement after its head, never above it.
	@Test
	public void neverGoesAboveVersion() {
		String text = "#version 300 es\nprecision highp float;\n" +
				"out vec4 color;\nvoid main() { color = vec4(1.0); }\n";
		assertEquals("#version 300 es\nprecision highp float;\n" +
						"uniform float time;\nout vec4 color;\n" +
						"void main() { color = vec4(1.0); }\n",
				add(text, "uniform float time;"));
	}

	// SPEC 3.1: a host directive is not allowed inside #if blocks; a
	// uniform inside one is not the place to add after.
	@Test
	public void neverLandsInsideAConditional() {
		String text = """
				precision mediump float;
				uniform vec2 resolution;
				#ifdef USE_BACKBUFFER
				uniform sampler2D backbuffer;
				#endif
				void main() {}
				""";
		String after = add(text, "#pragma se input \"noise.png\"\n" +
				"uniform sampler2D noise;");
		int pragma = after.indexOf("#pragma se input");
		assertTrue(after, pragma < after.indexOf("#ifdef"));
		assertTrue(after, after.startsWith("precision mediump float;\n" +
				"uniform vec2 resolution;\n#pragma se input"));
	}

	// Where nothing but conditional blocks and precision lines come
	// first, the statement follows them.
	@Test
	public void followsThePrecisionBlock() {
		String text = """
				#ifdef GL_FRAGMENT_PRECISION_HIGH
				precision highp float;
				#else
				precision mediump float;
				#endif
				void main() {}
				""";
		assertTrue(add(text, "uniform float time;").startsWith(
				"#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n" +
						"#else\nprecision mediump float;\n#endif\n\n" +
						"uniform float time;\nvoid main()"));
	}

	// A file name with regex characters is a name, not a pattern: it
	// was a crash on the main thread.
	@Test
	public void aFileNameWithBracketsIsInsertedAsItIs() {
		String shader = "precision mediump float;\nuniform vec2 r;\n" +
				"void main() {}\n";
		for (String image : new String[]{"textures/noise(1.png",
				"textures/noise[1.png", "textures/a{b}.png",
				"textures/x).png", "textures/a+b*.png"}) {
			InsertTexture.Lines lines = InsertTexture.of(shader,
					"main.frag", image);
			String after = add(shader, lines.statement());
			assertTrue(image, after.contains(lines.statement()));
			// Added again, it takes the place of the same lines.
			assertEquals(image, after, add(after, lines.statement()));
		}
	}

	private static String add(String text, String statement) {
		Document document = new Document(Text.of(text));
		AddUniform.apply(document, statement);
		return document.text().toString();
	}
}
