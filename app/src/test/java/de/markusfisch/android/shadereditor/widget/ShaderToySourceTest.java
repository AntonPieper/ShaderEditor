package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.editor.Document;
import de.markusfisch.android.shadereditor.editor.Text;

public class ShaderToySourceTest {
	@Test
	public void shaderToyEntryPoint() {
		assertTrue(ShaderToySource.needsConversion(
				"void mainImage(out vec4 fragColor, in vec2 fragCoord) {\n" +
						"\tfragColor = vec4(1.0);\n" +
						"}\n"));
		assertTrue(ShaderToySource.needsConversion(
				"float f;\nvoid  mainImage (out vec4 c, in vec2 p) {}"));
	}

	@Test
	public void mainEntryPoint() {
		assertFalse(ShaderToySource.needsConversion(
				"void main() {\n\tgl_FragColor = vec4(1.0);\n}\n"));
	}

	@Test
	public void bothEntryPoints() {
		assertFalse(ShaderToySource.needsConversion(
				"void mainImage(out vec4 c, in vec2 p) {}\n" +
						"void main() {\n\tmainImage(gl_FragColor, gl_FragCoord.xy);\n}\n"));
	}

	@Test
	public void mainImageWithoutDefinition() {
		assertFalse(ShaderToySource.needsConversion("float mainImage;"));
		assertFalse(ShaderToySource.needsConversion(""));
	}

	@Test(timeout = 1000)
	public void longLineWithoutEntryPoint() {
		assertFalse(ShaderToySource.needsConversion(
				"float x = " + "1.0 + ".repeat(10000) + "1.0;"));
	}

	@Test(timeout = 1000)
	public void longLineWithEntryPoint() {
		assertTrue(ShaderToySource.needsConversion(
				"void mainImage(out vec4 c, in vec2 p) { c = vec4(" +
						"1.0 + ".repeat(10000) + "1.0); }"));
	}

	@Test
	public void conversionDeclaresTheRenamedUniforms() {
		String converted = ShaderToySource.convert(
				"void mainImage(out vec4 c, in vec2 p) {\n" +
						"\tc = vec4(p / iResolution.xy, " +
						"sin(iTime) + float(iFrame), 1.0);\n}\n");
		assertNotNull(converted);
		assertTrue(converted.contains("uniform float time;\n"));
		assertTrue(converted.contains("uniform int frame;\n"));
		// What a frame took and the frame rate are uniforms too.
		String timed = ShaderToySource.convert(
				"void mainImage(out vec4 c, in vec2 p) {\n" +
						"\tc = vec4(iTimeDelta * iFrameRate);\n}\n");
		assertNotNull(timed);
		assertTrue(timed.contains("uniform float timeDelta;\n" +
				"uniform float frameRate;\n"));
		assertTrue(timed.contains("c = vec4(timeDelta * frameRate);"));
		assertTrue(converted.contains(
				"c = vec4(p / resolution.xy, sin(time) + float(frame), 1.0);"));
		assertNull(ShaderToySource.convert(converted));
	}

	// A port names its own time: only what it uses is declared.
	@Test
	public void onlyTheUniformsOfUsedInputsAreDeclared() {
		String converted = ShaderToySource.convert(
				"float time = 2.0;\n" +
						"void mainImage(out vec4 c, in vec2 p) {\n" +
						"\tc = vec4(p / iResolution.xy, time, 1.0);\n}\n");
		assertNotNull(converted);
		assertTrue(converted.contains("uniform vec2 resolution;\n"));
		assertFalse(converted.contains("uniform float time;"));
		assertFalse(converted.contains("uniform vec4 mouse;"));
		assertFalse(converted.contains("uniform vec4 date;"));
	}

	// #122: a converted shader must not be wrapped again when its
	// main() is cut, and pasting a ShaderToy shader still converts it.
	@Test
	public void onlyAnInsertedMainImageNeedsConversion() {
		String toy = "void mainImage(out vec4 c, in vec2 p) {\n" +
				"\tc = vec4(1.0);\n}\n";
		assertTrue(ShaderToySource.needsConversion(toy, toy));
		String converted = "precision mediump float;\n\n" + toy +
				"\n\nvoid main() {\n" +
				"\tvec4 fragment_color;\n" +
				"\tmainImage(fragment_color, gl_FragCoord.xy);\n" +
				"\tgl_FragColor = fragment_color;\n" +
				"}\n";
		String cut = converted.substring(0, converted.indexOf("void main()"));
		// The text alone looks like a ShaderToy shader again.
		assertTrue(ShaderToySource.needsConversion(cut));
		assertFalse(ShaderToySource.needsConversion(cut, ""));
		assertFalse(ShaderToySource.needsConversion(cut + "x", "x"));
		assertFalse(ShaderToySource.needsConversion(converted, converted));
	}

	// The same with the new editor's document: pasting a ShaderToy shader
	// converts it once, and cutting the main() of the converted shader
	// does not wrap it again.
	@Test
	public void theNewEditorConvertsOnlyWhatAnEditPutIn() {
		String toy = "void mainImage(out vec4 c, in vec2 p) {\n" +
				"\tc = vec4(1.0);\n}\n";
		Document document = new Document(Text.EMPTY);
		List<Text> inserted = new ArrayList<>();
		document.addListener(new Document.Listener() {
			@Override
			public void onChange(Document.Change change, Text before) {
				inserted.add(change.inserted());
			}
		});

		document.replace(0, 0, Text.of(toy));
		String converted = ShaderToySource.convert(
				document.text().toString(), inserted);
		assertNotNull(converted);
		assertEquals(converted.indexOf("void main()"),
				converted.lastIndexOf("void main()"));

		inserted.clear();
		document.replace(0, document.text().length(), Text.of(converted));
		assertNull(ShaderToySource.convert(document.text().toString(),
				inserted));

		inserted.clear();
		document.replace(converted.indexOf("void main()"),
				document.text().length(), Text.EMPTY);
		assertNull(ShaderToySource.convert(document.text().toString(),
				inserted));
	}
}
