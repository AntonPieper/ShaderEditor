package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyProject.Converted;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Input;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Kind;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Pass;

public class ShaderToyProjectTest {
	private static final String CODE =
			"void mainImage(out vec4 c, in vec2 p) {\n\tc = vec4(1.0);\n}\n";

	// The whole file once: the lines above the code, then the code as
	// it was.
	@Test
	public void anImageAloneIsTheRunningShader() {
		Converted converted = ShaderToyProject.ofJson(
				Fixtures.text("rings.json"));
		assertNull(converted.refusal());
		assertEquals("Rings", converted.name());
		assertEquals(List.of(), converted.warnings());
		assertEquals(List.of("README.md", "main.frag"),
				new ArrayList<>(converted.files().keySet()));
		String main = converted.files().get("main.frag");
		String code = ShaderToyJson.read(Fixtures.text("rings.json"))
				.shader().passes().get(0).code();
		assertEquals("#version 300 es\n" +
				"precision highp float;\n" +
				"precision highp int;\n" +
				"precision highp sampler2D;\n" +
				"precision highp sampler3D;\n" +
				"precision highp samplerCube;\n" +
				"\n" +
				"// Image of \"Rings\" by the tests, imported from " +
				"ShaderToy.\n" +
				"// README.md says what the import did.\n" +
				"\n" +
				"// ShaderToy's inputs, filled from the app's in main().\n" +
				"uniform vec2 resolution;\n" +
				"uniform float time;\n" +
				"uniform vec4 mouse;\n" +
				"\n" +
				"vec3 iResolution;\n" +
				"float iTime;\n" +
				"vec4 iMouse;\n" +
				"\n" +
				"void shaderToyInputs() {\n" +
				"\tiResolution = vec3(resolution, 1.0);\n" +
				"\tiTime = time;\n" +
				"\tiMouse = mouse;\n" +
				"}\n" +
				"\n" +
				"out vec4 shaderToyColor;\n" +
				"\n" +
				"void mainImage(out vec4 c, in vec2 f);\n" +
				"\n" +
				"void main() {\n" +
				"\tshaderToyInputs();\n" +
				"\tvec4 color = vec4(1e20);\n" +
				"\tmainImage(color, gl_FragCoord.xy);\n" +
				"\tshaderToyColor = vec4(color.rgb, 1.0);\n" +
				"}\n" +
				"\n" + code, main);
		assertEquals(ShaderToyProject.ofJson(Fixtures.text("rings.json")),
				converted);
	}

	@Test
	public void aBufferThatReadsItselfAndCommon() {
		Converted converted = ShaderToyProject.ofJson(
				Fixtures.text("trail.json"));
		assertEquals(List.of(), converted.warnings());
		assertEquals(List.of("README.md", "common.glsl", "empty.frag",
						"bufferA.frag", "main.frag"),
				new ArrayList<>(converted.files().keySet()));
		String buffer = converted.files().get("bufferA.frag");
		assertTrue(buffer, buffer.contains(
				"#pragma se target format=rgba16f initial=\"empty.frag\"\n" +
						"\n" +
						"// iChannel0: Buffer A, as it was the frame before.\n" +
						"#pragma se input \"bufferA.frag\" frame=previous " +
						"filter=linear\n" +
						"uniform sampler2D iChannel0;\n"));
		assertTrue(buffer, buffer.contains("\tshaderToyColor = color;\n"));
		String main = converted.files().get("main.frag");
		assertTrue(main, main.contains(
				"// iChannel0: Buffer A, as drawn in this frame.\n" +
						"#pragma se input \"bufferA.frag\" filter=linear\n" +
						"uniform sampler2D iChannel0;\n"));
		assertFalse(main, main.contains("se target"));
		// Common is the author's file, included above each tab's code.
		String common = ShaderToyJson.read(Fixtures.text("trail.json"))
				.shader().passes().get(1).code();
		assertEquals(common, converted.files().get("common.glsl"));
		for (String file : new String[]{buffer, main}) {
			assertTrue(file, file.indexOf("#include \"common.glsl\"\n") >
					file.indexOf("void main() {"));
			assertTrue(file, file.indexOf("#include \"common.glsl\"\n") <
					file.lastIndexOf("void mainImage("));
		}
		assertTrue(converted.files().get("empty.frag").contains(
				"color = vec4(0.0);"));
	}

	@Test
	public void theReadmeNamesTheAuthorTheFilesAndWhatIsMissing() {
		assertEquals("# Trail\n\n" +
				"By the tests on ShaderToy: " +
				"https://www.shadertoy.com/view/fixture2\n\n" +
				ShaderToyReadmeTest.DEFAULT +
				"## Files\n" +
				"- [main.frag](main.frag) Image: what you see\n" +
				"- [bufferA.frag](bufferA.frag) Buffer A\n" +
				"- [common.glsl](common.glsl) Common: code every pass " +
				"includes\n" +
				"- [empty.frag](empty.frag) What a buffer holds before " +
				"the first frame\n\n" +
				"Buffers keep 16-bit floats here; ShaderToy keeps 32-bit " +
				"ones. A shader that packs data into the bits of a float " +
				"may differ.\n",
				ShaderToyProject.ofJson(Fixtures.text("trail.json"))
						.files().get("README.md"));
		Converted media = ShaderToyProject.ofJson(
				Fixtures.text("media.json"));
		String readme = media.files().get("README.md");
		assertTrue(readme, readme.contains(
				"- [cubeA.glsl](cubeA.glsl) Kept from ShaderToy, not run\n"));
		assertTrue(readme, readme.endsWith("\n## Not carried over\n- " +
				String.join("\n- ", media.warnings()) + "\n"));
	}

	// The licence an author put at the top of the code is what the
	// README states: of Image first, else of the first tab that has one.
	@Test
	public void theReadmeStatesTheLicenceOfTheCode() {
		String mit = "// MIT License, Copyright 2024 Someone\n" + CODE;
		assertTrue(ShaderToyProject.of(shader(image(mit))).files()
				.get("README.md").contains("Its code begins with this " +
						"licence:\n\n> MIT License, Copyright 2024 Someone\n"));
		String readme = ShaderToyProject.of(shader(image(CODE),
				new Pass(Kind.BUFFER, "Buffer A", "/* CC0 */\n" + CODE,
						List.of(), "257"),
				new Pass(Kind.COMMON, "Common",
						"// Public domain.\nfloat one() { return 1.0; }\n",
						List.of(), ""))).files().get("README.md");
		assertTrue(readme, readme.contains("licence:\n\n> Public domain.\n"));
		assertFalse(readme, readme.contains("Creative Commons"));
	}

	// ShaderToy draws the buffers in the order they stand in, then
	// Image; a read gets the picture finished last.
	@Test
	public void whichFrameAReadSeesFollowsTheOrderOfTheBuffers() {
		// As ShaderToy reads them: what is neither linear nor mipmap
		// is nearest, and what is not clamp repeats.
		Input readsA = buffer(0, "idA", "none", "clamp");
		Input readsB = buffer(1, "idB", "linear", "mirror");
		String code = "void mainImage(out vec4 c, in vec2 p) {\n" +
				"\tc = texture(iChannel0, p) + texture(iChannel1, p);\n}\n";
		// B stands first, so it is drawn first.
		Converted converted = ShaderToyProject.of(shader(
				new Pass(Kind.IMAGE, "Image", code,
						List.of(readsA, readsB), ""),
				new Pass(Kind.BUFFER, "Buffer B", code,
						List.of(readsA, readsB), "idB"),
				new Pass(Kind.BUFFER, "Buffer A", code,
						List.of(readsA, readsB), "idA")));
		assertEquals(List.of(
				"#pragma se input \"bufferA.frag\"",
				"#pragma se input \"bufferB.frag\" filter=linear " +
						"wrap=repeat"),
				inputs(converted, "main.frag"));
		assertEquals(List.of(
				"#pragma se input \"bufferA.frag\" frame=previous",
				"#pragma se input \"bufferB.frag\" frame=previous " +
						"filter=linear wrap=repeat"),
				inputs(converted, "bufferB.frag"));
		assertEquals(List.of(
				"#pragma se input \"bufferA.frag\" frame=previous",
				"#pragma se input \"bufferB.frag\" filter=linear " +
						"wrap=repeat"),
				inputs(converted, "bufferA.frag"));
	}

	@Test
	public void everyInputThatIsNotCarriedOverIsAWarning() {
		Converted converted = ShaderToyProject.ofJson(
				Fixtures.text("media.json"));
		assertNull(converted.refusal());
		assertEquals(List.of(
				"The Cube A tab draws a cubemap, which the app cannot do. " +
						"Its code is kept in cubeA.glsl and is not run.",
				"The Sound tab makes sound, which the app does not do. " +
						"Its code is kept in sound.glsl and is not run.",
				"bufferA.frag: iChannel0 read the keyboard, which the " +
						"app does not have. It reads black: no key is down.",
				"bufferA.frag: iChannel1 read the webcam, which the " +
						"import does not attach the app's camera to. It " +
						"reads black.",
				"bufferA.frag: iChannel2 read the microphone, which the " +
						"app does not turn into a picture. It reads black.",
				"bufferB.frag: iChannel0 read music (/media/a/tune.mp3), " +
						"which the app does not turn into a picture. It " +
						"reads black.",
				"bufferB.frag: iChannel1 read music " +
						"(https://soundcloud.com/example/tune), which the " +
						"app does not turn into a picture. It reads black.",
				"bufferB.frag: iChannel2 read a video " +
						"(/media/a/clip.webm), which the app does not " +
						"play. It reads black.",
				"bufferC.frag: iChannel0 read Cube A, a pass that draws " +
						"a cubemap, which the app cannot do. It reads black.",
				"bufferC.frag: iChannel1 read a texture of ShaderToy " +
						"(/media/a/wood.jpg), which is not part of the " +
						"shader. It reads black until you attach a " +
						"picture; the file says how.",
				"bufferC.frag: iChannel2 read something of the kind " +
						"\"hologram\", which the import does not know. It " +
						"reads black.",
				"bufferC.frag: iChannel3 read a buffer, which this " +
						"shader does not have. It reads black.",
				"main.frag: iChannel0 read a texture of ShaderToy " +
						"(/media/a/stone.jpg), which is not part of the " +
						"shader. It reads black until you attach a " +
						"picture; the file says how.",
				"main.frag: iChannel1 read a cubemap of ShaderToy " +
						"(/media/a/sky.jpg), which is not part of the " +
						"shader. It reads black until you attach a " +
						"picture; the file says how.",
				"main.frag: iChannel2 read a volume texture of ShaderToy " +
						"(/media/a/noise.bin), which the app cannot load. " +
						"It reads black."),
				converted.warnings());
		assertEquals(List.of("README.md", "empty.frag", "bufferA.frag",
						"bufferB.frag", "bufferC.frag", "cubeA.glsl",
						"sound.glsl", "main.frag"),
				new ArrayList<>(converted.files().keySet()));
	}

	// A texture is declared without an input, so it reads black, with
	// the line that attaches a picture ready above it.
	@Test
	public void aMissingTextureIsDeclaredAndSaysHowToAttachOne() {
		Converted converted = ShaderToyProject.ofJson(
				Fixtures.text("media.json"));
		String main = converted.files().get("main.frag");
		assertTrue(main, main.contains(
				"// iChannel0 read a texture of ShaderToy " +
						"(/media/a/stone.jpg),\n" +
						"// which is not part of the shader. It reads " +
						"black until you attach a picture; the file says " +
						"how.\n" +
						"// Put a picture into this folder and take the " +
						"\"//\" off the next line:\n" +
						"// #pragma se input \"channel0.png\" " +
						"mipmap=linear wrap=repeat\n" +
						"uniform sampler2D iChannel0;\n"));
		assertTrue(main, main.contains("uniform samplerCube iChannel1;\n"));
		assertTrue(main, main.contains("uniform sampler3D iChannel2;\n"));
		assertEquals(List.of("#pragma se input \"bufferA.frag\" " +
				"filter=linear"), inputs(converted, "main.frag"));
		String c = converted.files().get("bufferC.frag");
		assertTrue(c, c.contains(
				"// ShaderToy read it upside down; the app reads a " +
						"picture upright.\n" +
						"// Put a picture into this folder and take the " +
						"\"//\" off the next line:\n" +
						"// #pragma se input \"channel1.png\" " +
						"filter=nearest\n" +
						"uniform sampler2D iChannel1;\n"));
		assertTrue(c, c.contains("uniform samplerCube iChannel0;\n"));
		// With mipmaps, as there.
		assertEquals(List.of("#pragma se input \"bufferB.frag\" " +
				"frame=previous filter=linear mipmap=linear"),
				inputs(converted, "bufferA.frag"));
	}

	// What a frame took and the frame rate are the app's uniforms, so
	// nothing is said of them; what has no uniform has one value.
	@Test
	public void frameTimingIsTheAppsAndTheRestIsSetToOneValue() {
		Converted converted = ShaderToyProject.ofJson(
				Fixtures.text("media.json"));
		String main = converted.files().get("main.frag");
		assertTrue(main, main.contains("uniform float timeDelta;\n" +
				"uniform float frameRate;\n"));
		assertTrue(main, main.contains("\tiTimeDelta = timeDelta;\n" +
				"\tiFrameRate = frameRate;\n"));
		assertTrue(main, main.contains(
				"// ShaderToy's mark of a slow device.\n" +
						"#define HW_PERFORMANCE 0\n"));
		String b = converted.files().get("bufferB.frag");
		assertTrue(b, b.contains("float iSampleRate;\n" +
				"float iChannelTime[4];\n"));
		assertTrue(b, b.contains("\tiSampleRate = 44100.0;\n" +
				"\tiChannelTime = float[4](0.0, 0.0, 0.0, 0.0);\n"));
		assertTrue(converted.files().get("bufferA.frag").contains(
				"uniform vec4 date;\n"));
		// The size of what a channel reads; nothing on the others.
		String ripples = ShaderToyProject.ofJson(
				Fixtures.text("ripples.json")).files().get("main.frag");
		assertTrue(ripples, ripples.contains(
				"\tiChannelResolution = vec3[4](" +
						"vec3(vec2(textureSize(iChannel0, 0)), 1.0), " +
						"vec3(vec2(textureSize(iChannel1, 0)), 1.0), " +
						"vec3(0.0), vec3(0.0));\n"));
	}

	// A channel the code never names is left out, and one it names
	// that had nothing attached reads black as it did.
	@Test
	public void onlyTheChannelsTheCodeNamesAreDeclared() {
		Converted converted = ShaderToyProject.of(shader(
				new Pass(Kind.IMAGE, "Image",
						"// iChannel2 is not used.\n" +
								"void mainImage(out vec4 c, in vec2 p) {\n" +
								"\tc = texture(iChannel1, p);\n}\n",
						List.of(buffer(0, "idA", "linear", "clamp"),
								buffer(2, "idA", "linear", "clamp")), ""),
				new Pass(Kind.BUFFER, "Buf A", CODE, List.of(), "idA")));
		assertEquals(List.of(), converted.warnings());
		String main = converted.files().get("main.frag");
		assertEquals(List.of(), inputs(converted, "main.frag"));
		assertFalse(main, main.contains("uniform sampler2D iChannel0;"));
		assertFalse(main, main.contains("uniform sampler2D iChannel2;"));
		assertTrue(main, main.contains(
				"// iChannel1: nothing was attached to it on ShaderToy. " +
						"It reads black.\n" +
						"uniform sampler2D iChannel1;\n"));
		// Nobody reads the buffer's frame before: it has no start.
		assertTrue(converted.files().get("bufferA.frag").contains(
				"#pragma se target format=rgba16f\n"));
		assertFalse(converted.files().containsKey("empty.frag"));
	}

	@Test
	public void tabsAsCodeListTheLinesAChannelMayGet() {
		Converted converted = ShaderToyProject.of(ShaderToyShader.ofTabs(
				"Tabs", java.util.Map.of(
						"image", "void mainImage(out vec4 c, in vec2 p) {\n" +
								"\tc = texture(iChannel0, p);\n}\n",
						"Buffer B", "void mainImage(out vec4 c, in vec2 p) " +
								"{\n\tc = texture(iChannel0, p);\n}\n",
						"buffer a", CODE,
						"Common", "float one() { return 1.0; }\n")));
		assertEquals("Tabs", converted.name());
		assertEquals(List.of("README.md", "common.glsl", "bufferA.frag",
						"bufferB.frag", "main.frag"),
				new ArrayList<>(converted.files().keySet()));
		assertEquals(List.of(
				"bufferB.frag: iChannel0 is not attached, because the " +
						"code does not say what a channel reads. It reads " +
						"black; the file lists the lines to choose from.",
				"main.frag: iChannel0 is not attached, because the code " +
						"does not say what a channel reads. It reads " +
						"black; the file lists the lines to choose from."),
				converted.warnings());
		String b = converted.files().get("bufferB.frag");
		assertTrue(b, b.contains(
				"// iChannel0: the code does not say what this channel " +
						"reads, so it reads black.\n" +
						"// Take the \"//\" off the line of what it " +
						"reads:\n" +
						"// #pragma se input \"bufferA.frag\"\n" +
						"// #pragma se input \"bufferB.frag\" " +
						"frame=previous\n" +
						"// #pragma se input \"channel0.png\"\n" +
						"uniform sampler2D iChannel0;\n"));
	}

	@Test
	public void namesThatCollide() {
		// Two buffers of one letter cannot both be bufferA.frag.
		assertEquals("two passes write Buffer A", ShaderToyProject.of(shader(
				image(CODE),
				new Pass(Kind.BUFFER, "Buffer A", CODE, List.of(), "257"),
				new Pass(Kind.BUFFER, "Buf A", CODE, List.of(), "x")))
				.refusal());
		// A letter comes from the id, then the name, then what is free.
		Converted lettered = ShaderToyProject.of(shader(image(CODE),
				new Pass(Kind.BUFFER, "Buffer A", CODE, List.of(),
						"XsXGR8"),
				new Pass(Kind.BUFFER, "buf d", CODE, List.of(), "x"),
				new Pass(Kind.BUFFER, "state", CODE, List.of(), "y")));
		assertEquals(List.of("README.md", "bufferB.frag", "bufferD.frag",
						"bufferA.frag", "main.frag"),
				new ArrayList<>(lettered.files().keySet()));
		// Tabs that are kept and not run never share a file.
		Converted kept = ShaderToyProject.of(shader(image(CODE),
				new Pass(Kind.OTHER, "a/b", "one", List.of(), ""),
				new Pass(Kind.OTHER, "a?b", "two", List.of(), ""),
				new Pass(Kind.OTHER, "common", "four", List.of(), ""),
				new Pass(Kind.COMMON, "Common", "five", List.of(), ""),
				new Pass(Kind.COMMON, "Common", "six\n", List.of(), ""),
				new Pass(Kind.VOLUME, "Volume A", "seven", List.of(), ""),
				new Pass(Kind.SOUND, "Sound", "three", List.of(), "")));
		assertEquals("one\n", kept.files().get("a_b.glsl"));
		assertEquals("two\n", kept.files().get("a_b 2.glsl"));
		assertEquals("three\n", kept.files().get("sound.glsl"));
		assertEquals("four\n", kept.files().get("common 2.glsl"));
		// ShaderToy puts every Common above a tab, in their order.
		assertEquals("five\nsix\n", kept.files().get("common.glsl"));
		assertEquals("seven\n", kept.files().get("volumeA.glsl"));
		assertEquals("The Volume A tab draws a volume texture, which the " +
				"app cannot do. Its code is kept in volumeA.glsl and is " +
				"not run.", kept.warnings().get(3));
		assertEquals("The tab a/b is of a kind the import does not know. " +
				"Its code is kept in a_b.glsl and is not run.",
				kept.warnings().get(0));
	}

	@Test
	public void codeThatDefinesMainIsKeptAsItIs() {
		String code = "// void mainImage is not here\n" +
				"void main() {\n\tgl_FragColor = vec4(1.0);\n}";
		Converted converted = ShaderToyProject.of(shader(image(code)));
		assertEquals(code + "\n", converted.files().get("main.frag"));
		assertEquals(List.of("main.frag defines main itself, so it was " +
				"kept as it is, without ShaderToy's inputs."),
				converted.warnings());
		// In a comment it is no definition.
		String commented = "// void main() of old\n" + CODE;
		assertEquals(List.of(), ShaderToyProject.of(shader(
				image(commented))).warnings());
	}

	@Test
	public void whatIsNoShaderToShowIsRefused() {
		assertEquals("it has no Image pass", ShaderToyProject.of(shader(
				new Pass(Kind.BUFFER, "Buffer A", CODE, List.of(), "257")))
				.refusal());
		assertEquals("it has two Image passes", ShaderToyProject.of(shader(
				image(CODE), image(CODE))).refusal());
		Converted broken = ShaderToyProject.ofJson("{\"renderpass\": [");
		assertEquals("the JSON is cut off or broken (line 1: the text " +
				"ends in the middle)", broken.refusal());
		assertTrue(broken.files().isEmpty());
	}

	@Test(timeout = 10000)
	public void twoMegabytes() {
		String code = "// A long comment.\\n".repeat(110000);
		Converted converted = ShaderToyProject.ofJson(
				"{\"Shader\": {\"renderpass\": [{\"type\": \"image\", " +
						"\"name\": \"Image\", \"code\": \"" + code +
						"void mainImage(out vec4 c, in vec2 p) {}\"}]}}");
		assertNull(converted.refusal());
		assertTrue(converted.files().get("main.frag").length() > 2000000);
	}

	private static ShaderToyShader shader(Pass... passes) {
		return new ShaderToyShader(null, null, null, true,
				Arrays.asList(passes));
	}

	private static Pass image(String code) {
		return new Pass(Kind.IMAGE, "Image", code, List.of(), "");
	}

	private static Input buffer(int channel, String id, String filter,
			String wrap) {
		return new Input(channel, "buffer", id, "", filter, wrap, true);
	}

	// The input lines of a file that are switched on.
	private static List<String> inputs(Converted converted, String file) {
		List<String> inputs = new ArrayList<>();
		for (String line : converted.files().get(file).split("\n")) {
			if (line.startsWith("#pragma se input")) {
				inputs.add(line);
			}
		}
		return inputs;
	}
}
