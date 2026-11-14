package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

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
	public void inputsWithAnEqualUniformAreRenamed() {
		assertEquals("resolution time time frame mouse date",
				ShaderToySource.rename(
						"iResolution iTime iGlobalTime iFrame iMouse iDate"));
	}

	@Test
	public void onlyWholeNamesAreRenamed() {
		assertEquals("iTimeDelta iChannel0 iChannelResolution[0] " +
						"iSampleRate myiTime iFrameRate",
				ShaderToySource.rename("iTimeDelta iChannel0 " +
						"iChannelResolution[0] iSampleRate myiTime " +
						"iFrameRate"));
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
		assertEquals("uniform float time;\n\n",
				ShaderToySource.declarations("iTime + iGlobalTime"));
		assertEquals("", ShaderToySource.declarations("myiTime iTimeDelta"));
	}
}
