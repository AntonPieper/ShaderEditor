package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OutlineTest {
	private static final Pattern UNIFORM = Pattern.compile(
			"^uniform\\s+\\w+\\s+(\\w+)", Pattern.MULTILINE);
	private static final Pattern FUNCTION = Pattern.compile(
			"^\\w+\\s+(\\w+)\\s*\\(", Pattern.MULTILINE);

	@Test
	public void bundledShaders() throws IOException {
		for (Map.Entry<String, String> shader :
				BundledShaders.read().entrySet()) {
			String text = shader.getValue();
			List<String> outline = outline(text);
			int found = 0;
			for (Matcher m = UNIFORM.matcher(text); m.find(); ++found) {
				assertTrue(shader.getKey() + ": " + m.group(1),
						outline.contains("UNIFORM " + m.group(1)));
			}
			for (Matcher m = FUNCTION.matcher(text); m.find(); ++found) {
				assertTrue(shader.getKey() + ": " + m.group(1),
						outline.contains("FUNCTION " + m.group(1)));
			}
			assertTrue(shader.getKey(), found > 0);
		}
	}

	@Test
	public void declarationsOfEveryKind() {
		assertEquals(Arrays.asList(
						"MACRO PI",
						"UNIFORM resolution",
						"UNIFORM pointers",
						"VARIABLE a",
						"VARIABLE b",
						"VARIABLE c",
						"TYPE Ray",
						"VARIABLE fragColor",
						"FUNCTION trace",
						"FUNCTION main"),
				outline("#define PI 3.14159\n" +
						"precision mediump float;\n" +
						"uniform vec2 resolution;\n" +
						"uniform vec3 pointers[10];\n" +
						"const float a = max(1.0, 2.0), b, c[2] = float[2](a, b);\n" +
						"struct Ray { vec3 o; vec3 d; };\n" +
						"layout(location = 0) out vec4 fragColor;\n" +
						"float trace(Ray r, float t) { float local = t; return local; }\n" +
						"void main() { vec3 inner = vec3(0.0); }\n"));
	}

	@Test
	public void declarationPositions() {
		String text = "float x;\nvoid main() {}";
		Outline.Declaration main = Outline.of(GlslLexer.lex(text), text)
				.declarations().get(1);
		assertEquals(new Outline.Declaration("main", Outline.Kind.FUNCTION,
				14, 18), main);
	}

	@Test
	public void declarationsInsideConditionals() {
		assertEquals(Arrays.asList(
						"VARIABLE high",
						"MACRO Q",
						"VARIABLE low",
						"VARIABLE other"),
				outline("#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
						"float high;\n" +
						"#if Q > 1\n" +
						"#define Q 2\n" +
						"#endif\n" +
						"#else\n" +
						"float low;\n" +
						"#endif\n" +
						"float other;\n"));
	}

	@Test
	public void structWithAnInstanceName() {
		assertEquals(Arrays.asList(
						"TYPE Light",
						"VARIABLE sun",
						"TYPE Material",
						"UNIFORM lamp",
						"VARIABLE anonymous"),
				outline("struct Light { vec3 color; float power; } sun;\n" +
						"uniform struct Material { vec3 albedo; } lamp;\n" +
						"struct { int a; } anonymous;\n"));
	}

	@Test
	public void prototypeAndDefinition() {
		assertEquals(Arrays.asList(
						"FUNCTION shade",
						"FUNCTION main",
						"FUNCTION shade"),
				outline("vec3 shade(vec3 p);\n" +
						"void main() { gl_FragColor = vec4(shade(vec3(0.0)), 1.0); }\n" +
						"vec3 shade(vec3 p) { return p; }\n"));
	}

	@Test
	public void macroWithArguments() {
		assertEquals(Arrays.asList(
						"MACRO SQUARE",
						"MACRO EMPTY",
						"MACRO texture",
						"MACRO LONG",
						"VARIABLE after"),
				outline("#define SQUARE(x) ((x) * (x))\n" +
						"# /* c */ define EMPTY\n" +
						"#define texture texture2D\n" +
						"#define LONG \\\n" +
						"\tfloat inMacro;\n" +
						"float after;\n"));
	}

	@Test
	public void namesInCommentsAndDisabledCode() {
		assertEquals(Arrays.asList(
						"VARIABLE enabled",
						"VARIABLE inElse",
						"VARIABLE after"),
				outline("// float commented;\n" +
						"/* void hidden() {} */\n" +
						"float enabled;\n" +
						"#if 0\n" +
						"float disabled;\n" +
						"#define DISABLED\n" +
						"#ifdef X\n" +
						"float nested;\n" +
						"#else\n" +
						"float nestedElse;\n" +
						"#endif\n" +
						"#else\n" +
						"float inElse;\n" +
						"#endif\n" +
						"#if 0\n" +
						"float disabledAgain;\n" +
						"#endif\n" +
						"float after;\n"));
	}

	@Test
	public void unfinishedCode() {
		assertEquals(Arrays.asList("VARIABLE a", "FUNCTION f"),
				outline("float a;\nvoid f() {\n\tfloat b;\n\tif (a > 0.0) {\n"));
		assertEquals(Arrays.asList("FUNCTION f"), outline("vec3 f(vec3"));
		assertEquals(Arrays.asList(), outline("x = y\nz = 1;"));
		assertEquals(Arrays.asList(), outline(""));
	}

	@Test
	public void randomFragments() {
		String[] fragments = {"#if 0\n", "#else\n", "#endif\n", "#define ",
				"struct ", "uniform ", "float ", "Ray ", "x", "(", ")", "{",
				"}", "[", "]", ";", ",", "=", "/*", "*/", "//", "\n", " "};
		Random random = new Random(11);
		for (int n = 0; n < 5000; ++n) {
			StringBuilder sb = new StringBuilder();
			for (int i = random.nextInt(40); i-- > 0; ) {
				sb.append(fragments[random.nextInt(fragments.length)]);
			}
			String text = sb.toString();
			Tokens tokens = GlslLexer.lex(text);
			for (Outline.Declaration declaration :
					Outline.of(tokens, text).declarations()) {
				assertEquals(text, declaration.name(), text.substring(
						declaration.start(), declaration.end()));
			}
		}
	}

	@Test
	public void colourTypesKeepsTokensWithoutTypes() {
		String text = "float x; vec3 f() { return vec3(x); }";
		Tokens tokens = GlslLexer.lex(text);
		assertSame(tokens, Outline.of(tokens, text).colourTypes(tokens, text));
	}

	@NonNull
	private static List<String> outline(@NonNull String text) {
		List<String> result = new ArrayList<>();
		for (Outline.Declaration declaration :
				Outline.of(GlslLexer.lex(text), text).declarations()) {
			result.add(declaration.kind() + " " + declaration.name());
		}
		return result;
	}
}
