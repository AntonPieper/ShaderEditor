package de.markusfisch.android.shadereditor.project.shadertoy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Input;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Kind;
import de.markusfisch.android.shadereditor.project.shadertoy.ShaderToyShader.Pass;

public class ShaderToyJsonTest {
	private static final String PASS =
			"{\"type\": \"image\", \"name\": \"Image\", \"code\": \"c\"}";

	@Test
	public void theAnswerOfTheApi() {
		ShaderToyJson.Read read = ShaderToyJson.read(
				Fixtures.text("ripples.json"));
		assertNull(read.refusal());
		assertEquals(List.of(), read.notes());
		ShaderToyShader shader = read.shader();
		assertEquals("Ripples", shader.name());
		assertEquals("the tests", shader.author());
		assertEquals("fixture3", shader.id());
		assertTrue(shader.wired());
		assertEquals(3, shader.passes().size());
		Pass image = shader.passes().get(0);
		assertEquals(Kind.IMAGE, image.kind());
		assertTrue(image.code().contains("void mainImage("));
		assertEquals(List.of(
				new Input(0, "buffer", "257",
						"/media/previz/buffer00.png", "nearest", "clamp",
						true),
				new Input(1, "buffer", "257",
						"/media/previz/buffer00.png", "linear", "clamp",
						true)), image.inputs());
		Pass a = shader.passes().get(1);
		assertEquals(Kind.BUFFER, a.kind());
		assertEquals("Buffer A", a.name());
		assertEquals("257", a.output());
	}

	@Test
	public void theShapeOfTheSite() {
		ShaderToyJson.Read read = ShaderToyJson.read(
				Fixtures.text("trail.json"));
		assertNull(read.refusal());
		ShaderToyShader shader = read.shader();
		assertEquals("Trail", shader.name());
		assertEquals(List.of(Kind.IMAGE, Kind.COMMON, Kind.BUFFER),
				shader.passes().stream().map(Pass::kind).toList());
		Pass a = shader.passes().get(2);
		assertEquals("4dXGR8", a.output());
		assertEquals(List.of(new Input(0, "buffer", "4dXGR8",
				"/media/previz/buffer00.png", "linear", "clamp", true)),
				a.inputs());
	}

	// Without the API's "Shader" around it and without the site's list.
	@Test
	public void theShaderAlone() {
		ShaderToyJson.Read read = ShaderToyJson.read(
				"{\"renderpass\": [" + PASS + "]}");
		assertNull(read.refusal());
		assertNull(read.shader().name());
		assertEquals(List.of(new Pass(Kind.IMAGE, "Image", "c", List.of(),
				"")), read.shader().passes());
	}

	@Test
	public void everyInputKindAndSetting() {
		ShaderToyShader shader = ShaderToyJson.read(
				Fixtures.text("media.json")).shader();
		assertNotNull(shader);
		assertEquals(List.of(Kind.IMAGE, Kind.BUFFER, Kind.BUFFER,
						Kind.BUFFER, Kind.CUBEMAP, Kind.SOUND),
				shader.passes().stream().map(Pass::kind).toList());
		assertEquals(new Input(0, "texture", "7", "/media/a/stone.jpg",
				"mipmap", "repeat", true),
				shader.passes().get(0).inputs().get(0));
		assertEquals(new Input(1, "texture", "8", "/media/a/wood.jpg",
				"nearest", "clamp", false),
				shader.passes().get(3).inputs().get(1));
		assertEquals("hologram",
				shader.passes().get(3).inputs().get(2).kind());
	}

	@Test
	public void aListOfSeveralShadersGivesTheFirst() {
		ShaderToyJson.Read read = ShaderToyJson.read("[{\"renderpass\": [" +
				PASS + "]}, {\"renderpass\": []}]");
		assertNotNull(read.shader());
		assertEquals(List.of(
				"The text holds 2 shaders; the first was imported."),
				read.notes());
	}

	@Test
	public void anInputWithoutAChannelIsLeftOutAndTold() {
		ShaderToyJson.Read read = ShaderToyJson.read("{\"renderpass\": [{" +
				"\"type\": \"image\", \"name\": \"Image\", \"code\": \"\", " +
				"\"inputs\": [{\"channel\": 4}, {\"ctype\": \"buffer\"}, " +
				"{\"channel\": 1.5}, {\"channel\": 3}]}]}");
		assertEquals(List.of(new Input(3, "unknown", "", "", "linear",
				"clamp", true)), read.shader().passes().get(0).inputs());
		assertEquals(3, read.notes().size());
		assertEquals("An input of Image names no channel from 0 to 3 and " +
				"was left out.", read.notes().get(0));
	}

	@Test
	public void recognizedByItsContent() {
		assertTrue(ShaderToyJson.recognizes(Fixtures.text("rings.json")));
		assertTrue(ShaderToyJson.recognizes(Fixtures.text("trail.json")));
		assertTrue(ShaderToyJson.recognizes(
				"﻿\n {\"Error\": \"Shader not found\"}"));
		// Cut off: still ShaderToy's, so that the import says so.
		assertTrue(ShaderToyJson.recognizes("{\"renderpass\": [{\"co"));
		assertFalse(ShaderToyJson.recognizes(""));
		assertFalse(ShaderToyJson.recognizes("{\"projectVersion\": 1}"));
		assertFalse(ShaderToyJson.recognizes("[1, 2, 3]"));
		assertFalse(ShaderToyJson.recognizes(
				"void mainImage(out vec4 c, in vec2 p) {}"));
		assertFalse(ShaderToyJson.recognizes(
				"// \"renderpass\"\nvoid main() {}"));
	}

	// One clear refusal for each wrong shape, never a crash.
	@Test
	public void wrongShapesAreRefused() {
		String[][] cases = {
				{"{\"Error\": \"Shader not found\"}",
						"ShaderToy answered with an error: Shader not found"},
				{"{\"renderpass\": [{\"co",
						"the JSON is cut off or broken (line 1: the text " +
								"ends in the middle of a string)"},
				{"[]", "the list holds no shader"},
				{"[\"renderpass\"]", "it is not a shader of ShaderToy"},
				{"{\"Shader\": {\"info\": {}}, \"x\": \"renderpass\"}",
						"it has no list of passes (\"renderpass\")"},
				{"{\"renderpass\": {}}",
						"it has no list of passes (\"renderpass\")"},
				{"{\"renderpass\": []}", "it has no passes"},
				{"{\"renderpass\": [1]}", "pass 1 is not an object"},
				{"{\"renderpass\": [{\"name\": \"Image\"}]}",
						"Image (pass 1) has no code"},
				{"{\"renderpass\": [" + PASS + ", {\"type\": \"buffer\", " +
						"\"code\": 3}]}", "buffer (pass 2) has no code"},
				{"{\"renderpass\": [{\"name\": \"Image\", \"code\": \"\", " +
						"\"inputs\": {}}]}",
						"the inputs of Image are not a list"},
				{"{\"renderpass\": [{\"name\": \"Image\", \"code\": \"\", " +
						"\"inputs\": [3]}]}",
						"an input of Image is not an object"},
		};
		for (String[] wrong : cases) {
			ShaderToyJson.Read read = ShaderToyJson.read(wrong[0]);
			assertNull(wrong[0], read.shader());
			assertEquals(wrong[0], wrong[1], read.refusal());
		}
	}

	// A wrong type where a text belongs does not stop the reading.
	@Test
	public void wrongTypesOfWhatIsNotNeededAreDefaults() {
		ShaderToyShader shader = ShaderToyJson.read("{\"info\": [], " +
				"\"renderpass\": [{\"type\": 5, \"name\": null, " +
				"\"code\": \"\", \"inputs\": null, \"outputs\": [3], " +
				"\"x\": {\"y\": [{}]}}]}").shader();
		assertNotNull(shader);
		assertNull(shader.name());
		assertEquals(List.of(new Pass(Kind.OTHER, "pass 1", "", List.of(),
				"")), shader.passes());
	}

	@Test(timeout = 5000)
	public void twoMegabytes() {
		String code = "// A long comment.\\n".repeat(110000);
		String text = "{\"Shader\": {\"renderpass\": [{\"type\": " +
				"\"image\", \"name\": \"Image\", \"code\": \"" + code +
				"void mainImage(out vec4 c, in vec2 p) {}\"}]}}";
		assertTrue(text.length() > 2 << 20);
		assertTrue(ShaderToyJson.recognizes(text));
		ShaderToyJson.Read read = ShaderToyJson.read(text);
		assertNull(read.refusal());
		assertTrue(read.shader().passes().get(0).code().length() > 2000000);
	}
}
