package de.markusfisch.android.shadereditor.project.legacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectFiles;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.LibraryTexture;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.Result;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter.TextureFile;

public class LegacyShaderConverterTest {
	private static final Map<String, LibraryTexture> LIBRARY = Map.of(
			"noise", new LibraryTexture(false, 256, 128),
			"sky", new LibraryTexture(true, 64, 96),
			"cameraBack", new LibraryTexture(false, 8, 8));
	private static final String NOISE_DEFAULT =
			"#pragma se input \"textures/noise.png\" " +
					"min=nearest mag=linear wrap=repeat";

	@Test
	public void namedTextureReadsTheLibraryTextureWithTheOldDefault() {
		Result result = convert("uniform sampler2D noise;\n");
		assertEquals(lines(NOISE_DEFAULT, "uniform sampler2D noise;"),
				result.shader());
		assertEquals(List.of(new TextureFile("noise", path(
				"textures/noise.png"), false)), result.textures());
		assertEquals(List.of(0, 1), result.lines());
		assertTrue(result.diagnostics().isEmpty());
	}

	@Test
	public void settingsCommentBecomesOptions() {
		assertEquals(lines("#pragma se input \"textures/noise.png\"",
						"uniform sampler2D noise;"),
				convert("uniform sampler2D noise; ///min:l;mag:l;s:c;t:c;\n")
						.shader());
		assertEquals(lines("#pragma se input \"textures/noise.png\" " +
								"min=linear mag=nearest mipmap=nearest " +
								"wrap=mirror,clamp",
						"\tuniform sampler2D noise;"),
				convert("\tuniform sampler2D noise;///min:ln;mag:n;s:m;t:c;\n")
						.shader());
	}

	@Test
	public void commentWithMoreThanSettingsIsKept() {
		assertEquals(lines("#pragma se input \"textures/noise.png\" " +
								"min=nearest mag=linear mipmap=linear " +
								"wrap=repeat",
						"uniform sampler2D noise; /// note: x"),
				convert("uniform sampler2D noise; /// note: x\n").shader());
	}

	@Test
	public void cubemapReadsTheRepackedAtlasWithoutWrap() {
		Result result = convert("uniform samplerCube sky; ///s:c;\n");
		assertEquals(lines("#pragma se input \"textures/sky.png\" " +
						"min=nearest mag=linear",
				"uniform samplerCube sky;"), result.shader());
		assertEquals(List.of(new TextureFile("sky",
				path("textures/sky.png"), true)), result.textures());
	}

	@Test
	public void samplerTypeDecidesHowTheStoredImageIsRead() {
		Result result = convert(lines("uniform sampler2D sky;",
				"uniform samplerCube noise;"));
		assertEquals(lines(
				"#pragma se input \"textures/sky.atlas.png\" " +
						"min=nearest mag=linear wrap=repeat",
				"uniform sampler2D sky;",
				"#pragma se input \"textures/noise.cube.png\" " +
						"min=nearest mag=linear",
				"uniform samplerCube noise;"), result.shader());
		assertEquals(List.of(
				new TextureFile("sky", path("textures/sky.atlas.png"), false),
				new TextureFile("noise", path("textures/noise.cube.png"),
						true)), result.textures());
	}

	@Test
	public void backbufferSettingsBecomeInputAndPresent() {
		Result result = convert(lines("#version 300 es",
				"uniform sampler2D backbuffer; ///min:l;mag:l;s:c;t:c;"));
		assertEquals(lines("#version 300 es",
				"#pragma se target present=linear",
				"#pragma se input filter=linear",
				"uniform sampler2D backbuffer;"), result.shader());
		assertEquals(List.of(1, 0, 0, 2), result.lines());
		assertTrue(result.diagnostics().isEmpty());
		assertEquals(lines("uniform sampler2D backbuffer;"),
				convert(lines("uniform sampler2D backbuffer;")).shader());
	}

	@Test
	public void backbufferPresetStartsFromAGeneratedShader() {
		Result result = convert(lines("uniform sampler2D backbuffer;///p:noise",
				"void main() {}"));
		assertEquals(lines("#pragma se target initial=\"presets/noise.glsl\"",
				"uniform sampler2D backbuffer;",
				"void main() {}"), result.shader());
		assertEquals(List.of(new TextureFile("noise", path(
				"textures/noise.png"), false)), result.textures());
		assertEquals(lines("#ifdef GL_FRAGMENT_PRECISION_HIGH",
				"precision highp float;",
				"#else",
				"precision mediump float;",
				"#endif",
				"",
				"#pragma se input \"../textures/noise.png\"",
				"uniform sampler2D image;",
				"uniform vec2 resolution;",
				"",
				"// The image is 256x128 pixels.",
				"void main() {",
				"\tfloat h = floor(resolution.x / 256.0 * 128.0 + 0.5);",
				"\tif (h < 1.0) {",
				"\t\tgl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);",
				"\t\treturn;",
				"\t}",
				"\tfloat y = mod(resolution.y - 1.0 - floor(gl_FragCoord.y), " +
						"h * 2.0);",
				"\tif (y >= h) {",
				"\t\ty = h * 2.0 - y - 1.0;",
				"\t}",
				"\tgl_FragColor = texture2D(image, vec2(",
				"\t\tgl_FragCoord.x / resolution.x,",
				"\t\t1.0 - (y + 0.5) / h));",
				"}"), result.files().get(path("presets/noise.glsl")));
		assertTrue(result.diagnostics().isEmpty());
		assertEquals(lines("uniform sampler2D backbuffer;///p:none"),
				convert(lines("uniform sampler2D backbuffer;///p:none"))
						.shader());
	}

	@Test
	public void cameraBuiltinsAndFtimeStayUnchanged() {
		String text = lines("#define FTIME_PERIOD 2.0",
				"uniform samplerExternalOES cameraBack;",
				"uniform samplerExternalOES other; ///min:l;",
				"uniform float time;",
				"uniform vec2 resolution;");
		Result result = convert(text);
		assertEquals(text, result.shader());
		assertTrue(result.textures().isEmpty());
		assertTrue(result.diagnostics().isEmpty());
		assertEquals(lines("#pragma se input filter=linear",
						"uniform samplerExternalOES cameraFront;"),
				convert(lines("uniform samplerExternalOES cameraFront; " +
						"///min:ll;s:r;")).shader());
	}

	@Test
	public void unknownNamesAndUnmatchedShapesStayAsWritten() {
		String text = lines("uniform sampler2D missing; ///min:l;",
				"uniform highp sampler2D noise;",
				"uniform sampler2D noise ;",
				"// uniform sampler2D noise;",
				"/* uniform sampler2D noise; */");
		Result result = convert(text);
		assertEquals(text, result.shader());
		assertTrue(result.textures().isEmpty());
		assertTrue(result.diagnostics().isEmpty());
	}

	@Test
	public void onlyTheFirstDeclarationOnALineIsRead() {
		assertEquals(lines(NOISE_DEFAULT,
						"uniform sampler2D noise; uniform sampler2D sky;"),
				convert(lines("uniform sampler2D noise; " +
						"uniform sampler2D sky;")).shader());
	}

	@Test
	public void codeBeforeADeclarationKeepsItsLine() {
		Result result = convert(lines("/* a",
				"*/ uniform sampler2D noise;"));
		assertEquals(lines("/* a", "*/", NOISE_DEFAULT,
				"uniform sampler2D noise;"), result.shader());
		assertEquals(List.of(1, 2, 0, 2), result.lines());
	}

	@Test
	public void backbufferSettingsInACommentStillScaleTheOutput() {
		assertEquals(lines("#pragma se target present=linear",
						"// uniform sampler2D backbuffer; ///mag:l;",
						"#pragma se input min=nearest mag=linear",
						"uniform sampler2D backbuffer;"),
				convert(lines("// uniform sampler2D backbuffer; ///mag:l;",
						"uniform sampler2D backbuffer;")).shader());
	}

	@Test
	public void backbufferMipmapConvertsToItsBaseFilter() {
		Result result = convert(lines("uniform sampler2D backbuffer; " +
				"///min:ll;mag:l;s:c;t:c;"));
		assertEquals(lines("#pragma se target present=linear",
				"#pragma se input filter=linear",
				"uniform sampler2D backbuffer;"), result.shader());
		assertEquals(List.of(new ProjectDiagnostic(
				ProjectDiagnostic.Severity.WARNING,
				"backbuffer reads with min=linear instead of a mipmap " +
						"filter, because pass outputs have no mipmaps; the " +
						"old mipmaps were made once and never updated",
				null, 1)), result.diagnostics());
	}

	@Test
	public void backbufferPresentationThatHasNoExactForm() {
		Result result = convert(lines("uniform sampler2D backbuffer; " +
				"///min:n;mag:l;s:r;t:c;"));
		assertEquals(lines("#pragma se target present=linear",
				"#pragma se input min=nearest mag=linear wrap=repeat,clamp",
				"uniform sampler2D backbuffer;"), result.shader());
		assertEquals(List.of(
				"The output is scaled to the screen with linear filtering " +
						"also when the quality is above 1, where the old " +
						"renderer used the backbuffer's min=nearest",
				"The edge pixels of the output are no longer blended with " +
						"the opposite edge when it is scaled to the screen, " +
						"which the backbuffer's wrap=repeat did"),
				messages(result));
	}

	@Test
	public void declarationInAConditionalBlockMovesOutOfIt() {
		Result result = convert(lines("float a;",
				"#ifdef FOO",
				"#if BAR",
				"uniform sampler2D noise; // why",
				"#endif",
				"#endif"));
		assertEquals(lines("float a;",
				NOISE_DEFAULT,
				"uniform sampler2D noise;",
				"#ifdef FOO",
				"#if BAR",
				" // why",
				"#endif",
				"#endif"), result.shader());
		assertEquals(List.of(1, 0, 4, 2, 3, 4, 5, 6), result.lines());
		assertEquals(List.of(new ProjectDiagnostic(
				ProjectDiagnostic.Severity.WARNING,
				"Moved uniform sampler2D noise out of the conditional block " +
						"on line 2, because #pragma se input cannot be " +
						"inside one", null, 4)), result.diagnostics());
	}

	@Test
	public void declarationsInSeveralConditionalBranchesStayAsWritten() {
		String text = lines("#ifdef FOO",
				"uniform sampler2D noise;",
				"#else",
				"uniform sampler2D noise;",
				"#endif");
		Result result = convert(text);
		assertEquals(text, result.shader());
		assertEquals(List.of(2, 4), lines(result.diagnostics()));
	}

	@Test
	public void libraryTextureNamedLikeTheCameraIsNotBound() {
		Result result = convert(lines("uniform sampler2D cameraBack;"));
		assertEquals(lines("uniform sampler2D cameraBack;"), result.shader());
		assertEquals(List.of(1), lines(result.diagnostics()));
	}

	@Test
	public void bindsNoMoreSamplersThanTheOldRenderer() {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 32; ++i) {
			text.append("uniform sampler2D t").append(i).append(";\n");
		}
		text.append("uniform sampler2D noise;\n")
				.append("uniform sampler2D backbuffer; ///mag:l;\n");
		Result result = convert(text.toString());
		assertEquals(text.toString(), result.shader());
	}

	@Test
	public void lineMapPointsDiagnosticsAtTheOriginalLines() {
		Result result = convert(lines("uniform sampler2D noise;",
				"uniform sampler2D cameraBack;",
				"uniform samplerCube sky;",
				"#include \"missing.glsl\"",
				"void main() {}"));
		assertEquals(List.of(0, 1, 2, 0, 3, 4, 5), result.lines());
		assertEquals(List.of(2), lines(result.diagnostics()));
		assertEquals(2, result.originalLine(3));
		Preparation preparation = prepare(result);
		ProjectDiagnostic missing = null;
		for (ProjectDiagnostic diagnostic : preparation.diagnostics()) {
			if (diagnostic.message().contains("missing.glsl")) {
				missing = diagnostic;
			}
		}
		assertNotNull(preparation.diagnostics().toString(), missing);
		assertEquals(6, missing.line());
		assertEquals(4, result.originalLine(missing.line()));
		assertEquals(0, result.originalLine(4));
		assertEquals(0, result.originalLine(8));
	}

	@Test
	public void normalizesLineEndings() {
		assertEquals(lines(NOISE_DEFAULT, "uniform sampler2D noise;",
						"void main() {}"),
				convert("uniform sampler2D noise;\r\nvoid main() {}\r\n")
						.shader());
		assertEquals("", convert("").shader());
	}

	@Test
	public void everyBundledSampleConvertsAndPrepares() throws IOException {
		File[] samples = new File("src/main/res/raw").listFiles(
				(dir, name) -> name.startsWith("sample_") &&
						name.endsWith(".glsl"));
		assertNotNull(samples);
		assertTrue(samples.length > 0);
		Arrays.sort(samples);
		for (File sample : samples) {
			Result result = convert(new String(
					Files.readAllBytes(sample.toPath()),
					StandardCharsets.UTF_8));
			assertTrue(sample.getName(), errors(result.diagnostics())
					.isEmpty());
			Preparation preparation = prepare(result);
			assertTrue(sample.getName() + ": " + preparation.diagnostics(),
					errors(preparation.diagnostics()).isEmpty());
			assertNotNull(sample.getName(), preparation.plan());
		}
	}

	private static Preparation prepare(Result result) {
		Map<ProjectPath, byte[]> files = new HashMap<>();
		for (Map.Entry<ProjectPath, String> file : result.files().entrySet()) {
			files.put(file.getKey(),
					file.getValue().getBytes(StandardCharsets.UTF_8));
		}
		for (TextureFile texture : result.textures()) {
			files.put(texture.path(), new byte[]{1, 2, 3});
		}
		ProjectFiles project = path -> files.containsKey(path)
				? new ProjectFiles.Content(files.get(path), "1")
				: null;
		return Preparation.prepare(project, LegacyShaderConverter.SHADER,
				ProjectLimits.DEFAULT);
	}

	private static List<ProjectDiagnostic> errors(
			List<ProjectDiagnostic> diagnostics) {
		List<ProjectDiagnostic> errors = new ArrayList<>();
		for (ProjectDiagnostic diagnostic : diagnostics) {
			if (diagnostic.severity() == ProjectDiagnostic.Severity.ERROR) {
				errors.add(diagnostic);
			}
		}
		return errors;
	}

	private static List<Integer> lines(List<ProjectDiagnostic> diagnostics) {
		List<Integer> lines = new ArrayList<>();
		for (ProjectDiagnostic diagnostic : diagnostics) {
			lines.add(diagnostic.line());
		}
		return lines;
	}

	private static List<String> messages(Result result) {
		List<String> messages = new ArrayList<>();
		for (ProjectDiagnostic diagnostic : result.diagnostics()) {
			messages.add(diagnostic.message());
		}
		return messages;
	}

	private static Result convert(String text) {
		return LegacyShaderConverter.convert(text, LIBRARY::get);
	}

	private static String lines(String... lines) {
		return String.join("\n", lines) + "\n";
	}

	private static ProjectPath path(String path) {
		return ProjectPath.parse(path).value();
	}
}
