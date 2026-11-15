package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** The docs of the built-in words, as the debug build ships them. */
public class GlslDocsTest {
	// The functions of the tables of section 8 of the GLSL ES 1.00
	// specification that a fragment shader can call; every other
	// function the docs have is new in 3.00.
	private static final Set<String> FUNCTIONS_100 = Set.of(
			"radians", "degrees", "sin", "cos", "tan", "asin", "acos",
			"atan", "pow", "exp", "log", "exp2", "log2", "sqrt",
			"inversesqrt", "abs", "sign", "floor", "ceil", "fract", "mod",
			"min", "max", "clamp", "mix", "step", "smoothstep", "length",
			"distance", "dot", "cross", "normalize", "faceforward",
			"reflect", "refract", "matrixCompMult", "lessThan",
			"lessThanEqual", "greaterThan", "greaterThanEqual", "equal",
			"notEqual", "any", "all", "not", "texture2D", "texture2DProj",
			"textureCube");
	// Of those, what 3.00 dropped.
	private static final Set<String> GONE_IN_300 = Set.of(
			"texture2D", "texture2DProj", "textureCube");

	@Test
	public void everyWordTheEditorColoursHasAnEntry() throws IOException {
		GlslDocs docs = read();
		List<String> missing = new ArrayList<>();
		for (char c : "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_"
				.toCharArray()) {
			for (String word : GlslWords.matching(String.valueOf(c))) {
				if (docs.lookup(word) == null) {
					missing.add(word);
				}
			}
		}
		assertEquals("[]", missing.toString());
	}

	@Test
	public void functionVersionsAreTheSpecifications() throws IOException {
		int functions = 0;
		for (GlslDocs.Entry entry : read().entries()) {
			if (entry.kind() != GlslDocs.Kind.FUNCTION) {
				continue;
			}
			++functions;
			String name = entry.name();
			// dFdx, dFdy and fwidth are an extension of 1.00.
			assertEquals(name, FUNCTIONS_100.contains(name), entry.es100());
			assertEquals(name, !GONE_IN_300.contains(name), entry.es300());
		}
		assertTrue(functions > FUNCTIONS_100.size());
	}

	@Test
	public void otherVersionsAreTheSpecifications() throws IOException {
		GlslDocs docs = read();
		// Keywords of 1.00 that 3.00 reserves, and the reverse.
		for (String word : new String[]{"attribute", "varying",
				"gl_FragColor", "gl_FragData", "gl_MaxVaryingVectors"}) {
			GlslDocs.Entry entry = docs.lookup(word);
			assertTrue(word, entry.es100() && !entry.es300());
		}
		for (String word : new String[]{"uint", "uvec3", "mat2x3",
				"sampler3D", "sampler2DShadow", "flat", "layout", "centroid",
				"switch", "case", "default", "gl_FragDepth",
				"gl_MaxProgramTexelOffset"}) {
			GlslDocs.Entry entry = docs.lookup(word);
			assertTrue(word, !entry.es100() && entry.es300());
		}
		for (String word : new String[]{"vec2", "mat4", "samplerCube",
				"precision", "invariant", "discard", "gl_FragCoord",
				"gl_PointCoord", "gl_MaxDrawBuffers"}) {
			GlslDocs.Entry entry = docs.lookup(word);
			assertTrue(word, entry.es100() && entry.es300());
		}
	}

	@Test
	public void formsOfAFunctionCallIt() throws IOException {
		for (GlslDocs.Entry entry : read().entries()) {
			if (entry.kind() == GlslDocs.Kind.FUNCTION) {
				assertFalse(entry.name(), entry.forms().isEmpty());
				for (String form : entry.forms()) {
					assertTrue(form, form.contains(" " + entry.name() + "(") &&
							form.endsWith(")"));
				}
			}
		}
	}

	@Test
	public void namesTheParametersOfAForm() {
		assertEquals(List.of("edge0", "edge1", "x"), GlslDocs.parameterNames(
				"T smoothstep(T edge0, T edge1, T x)"));
		assertEquals(List.of("m"), GlslDocs.parameterNames(
				"mat2 inverse(mat2 m)"));
		assertEquals(List.of(), GlslDocs.parameterNames("vec4 f()"));
	}

	@Test
	public void examplesAreAscii() throws IOException {
		for (GlslDocs.Entry entry : read().entries()) {
			for (String line : entry.example()) {
				assertTrue(line, line.chars().allMatch(c -> c < 128));
			}
		}
	}

	@Test
	public void staysSmall() {
		assertTrue(file().length() < 48 * 1024);
	}

	@Test
	public void readsABlock() throws IOException {
		GlslDocs docs = GlslDocs.read(new StringReader("""
				# A comment.
				step function 100 300
				form T step(T edge, T x)
				detail T is float.
				text 0 below edge, else 1.
				example float a = step(0.5, x);
				example float b = 1.0 - a;

				uint type 300
				text Unsigned.
				"""));
		GlslDocs.Entry step = docs.lookup("step");
		assertEquals(GlslDocs.Kind.FUNCTION, step.kind());
		assertTrue(step.es100() && step.es300());
		assertEquals(List.of("T step(T edge, T x)"), step.forms());
		assertEquals("T is float.", step.detail());
		assertEquals("0 below edge, else 1.", step.text());
		assertNull(step.note());
		assertEquals(List.of("float a = step(0.5, x);", "float b = 1.0 - a;"),
				step.example());
		assertFalse(docs.lookup("uint").es100());
		assertNull(docs.lookup("mix"));
	}

	@Test
	public void refusesABlockWithoutText() {
		assertRefused("step function 100\nform T step(T edge, T x)\n",
				"Line 1");
		assertRefused("step function 100\ntext a\n\nstep function 300\n" +
				"text b\n", "Line 4");
		assertRefused("step routine 100\ntext a\n", "Line 1");
		assertRefused("step function 110\ntext a\n", "Line 1");
		assertRefused("step function 100\ntext a\nsay b\n", "Line 3");
	}

	private static void assertRefused(String text, String where) {
		try {
			GlslDocs.read(new StringReader(text));
			fail(text);
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().startsWith(where));
		}
	}

	private static GlslDocs read() throws IOException {
		try (Reader reader = Files.newBufferedReader(file().toPath(),
				StandardCharsets.UTF_8)) {
			GlslDocs docs = GlslDocs.read(reader);
			assertNotNull(docs.lookup("smoothstep"));
			return docs;
		}
	}

	private static File file() {
		String relative = "src/debug/assets/glsl-docs.txt";
		for (String base : new String[]{".", "app"}) {
			File file = new File(base, relative);
			if (file.isFile()) {
				return file;
			}
		}
		throw new AssertionError("Cannot find " + relative);
	}
}
