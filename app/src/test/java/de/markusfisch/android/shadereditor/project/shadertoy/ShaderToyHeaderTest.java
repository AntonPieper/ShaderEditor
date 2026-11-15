package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ShaderToyHeaderTest {
	// ShaderToy's names are variables that a function fills from the
	// app's uniforms; only what the code names is declared.
	@Test
	public void theInputsTheCodeNames() {
		assertEquals("// ShaderToy's inputs, filled from the app's in " +
				"main().\n" +
				"uniform vec2 resolution;\n" +
				"uniform float time;\n" +
				"\n" +
				"vec3 iResolution;\n" +
				"float iGlobalTime;\n" +
				"float iTime;\n" +
				"\n" +
				"void shaderToyInputs() {\n" +
				"\tiResolution = vec3(resolution, 1.0);\n" +
				"\tiGlobalTime = time;\n" +
				"\tiTime = time;\n" +
				"}\n",
				ShaderToyHeader.inputs("iResolution.xy * iTime + " +
						"iGlobalTime + iTimeDeltas", List.of()));
		assertEquals("// ShaderToy's inputs, filled from the app's in " +
				"main().\n\nvoid shaderToyInputs() {\n}\n",
				ShaderToyHeader.inputs("fragCoord", List.of()));
	}

	// What a frame took and the frame rate are uniforms of the app
	// with ShaderToy's meaning; the rest has no uniform and one value.
	@Test
	public void frameTimingAndWhatHasNoUniform() {
		String header = ShaderToyHeader.inputs(
				"iTimeDelta iFrameRate iSampleRate iChannelTime[0] " +
						"iChannelResolution[2]",
				List.of(ShaderToyChannel.unattached(0),
						new ShaderToyChannel(2, "sampler3D", "", null)));
		assertTrue(header, header.startsWith(
				"// ShaderToy's inputs, filled from the app's in main().\n" +
						"uniform float timeDelta;\n" +
						"uniform float frameRate;\n\n"));
		assertTrue(header, header.endsWith("void shaderToyInputs() {\n" +
				"\tiTimeDelta = timeDelta;\n" +
				"\tiFrameRate = frameRate;\n" +
				"\tiSampleRate = 44100.0;\n" +
				"\tiChannelTime = float[4](0.0, 0.0, 0.0, 0.0);\n" +
				"\tiChannelResolution = vec3[4](" +
				"vec3(vec2(textureSize(iChannel0, 0)), 1.0), vec3(0.0), " +
				"vec3(textureSize(iChannel2, 0)), vec3(0.0));\n}\n"));
		assertTrue(header, header.contains("float iChannelTime[4];\n" +
				"vec3 iChannelResolution[4];\n"));
	}

	@Test
	public void aNameDeclaredOutsideEveryFunction() {
		String code = ShaderToyHeader.code(
				"#define time iTime\n" +
						"// float time;\n" +
						"#define LONG(a) \\\n" +
						"\tdate + a\n" +
						"struct Ray { float time; };\n" +
						"float speed(float time) { return time; }\n" +
						"float frame;\n", true);
		assertFalse(ShaderToyHeader.declares(code, "time"));
		assertFalse(ShaderToyHeader.declares(code, "date"));
		assertTrue(ShaderToyHeader.declares(code, "frame"));
		assertTrue(ShaderToyHeader.declares(code, "speed"));
		assertFalse(ShaderToyHeader.declares(code, "fram"));
	}

	// A global of the shader's own named as an app uniform that the
	// inputs declare gets another name throughout.
	@Test
	public void whatStandsBetweenTheInputsAndTheCode() {
		String shader = "float time;\nvec2 mouse;\n" +
				"float t() {\n#if HW_PERFORMANCE == 0\n" +
				"\ttime = iTime;\n#endif\n\treturn time; }\n";
		assertEquals("// ShaderToy's mark of a slow device.\n" +
				"#define HW_PERFORMANCE 0\n" +
				"// The shader has a \"time\" of its own, which is also " +
				"the name of the app's uniform read above.\n" +
				"#define time shaderToyOwn_time\n\n",
				ShaderToyHeader.defines(ShaderToyHeader.code(shader, false),
						ShaderToyHeader.code(shader, true)));
		// The code has its own "time" and says so with a macro: no
		// second one is put over it.
		String macro = "#define time iTime\nfloat t() { return time; }\n";
		assertEquals("", ShaderToyHeader.defines(
				ShaderToyHeader.code(macro, false),
				ShaderToyHeader.code(macro, true)));
	}

	// Comments go, lines stay where they were, whatever ends them.
	@Test
	public void codeWithoutCommentsAndOnOneLine() {
		String[] lines = ShaderToyHeader.code(
				"a /* x\r\ny */ b\r#define c d //e", false).split("\n");
		assertEquals(3, lines.length);
		assertEquals("a", lines[0].trim());
		assertEquals("b", lines[1].trim());
		assertEquals("#define c d", lines[2].trim());
		assertEquals("a b c", ShaderToyHeader.line(" a\n\tb  c "));
	}
}
