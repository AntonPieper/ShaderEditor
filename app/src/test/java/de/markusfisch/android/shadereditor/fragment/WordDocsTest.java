package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge;

/** What Look up and the row's fix add for the app's uniforms. */
public class WordDocsTest {
	@Test
	public void addsWhatAddUniformAdds() {
		assertEquals("uniform float time;",
				WordDocs.statement(WordDocs.uniform("time")));
		assertEquals("uniform sampler2D backbuffer;",
				WordDocs.statement(WordDocs.uniform("backbuffer")));
		assertNull(WordDocs.uniform("smoothstep"));
	}

	@Test
	public void fixesAnUndeclaredUniformOfTheApp() {
		assertEquals("time", only(WordDocs.undeclaredUniforms(List.of(
				"'time' : undeclared identifier"), Knowledge.NONE)));
		assertEquals("touch", only(WordDocs.undeclaredUniforms(List.of(
				"'x' : syntax error",
				"use of undeclared identifier 'touch'"), Knowledge.NONE)));
		// Not one of the app's, not undeclared, or declared after all.
		assertTrue(WordDocs.undeclaredUniforms(List.of(
				"'timeScale' : undeclared identifier"), Knowledge.NONE)
				.isEmpty());
		assertTrue(WordDocs.undeclaredUniforms(List.of(
				"'time' : redefinition"), Knowledge.NONE).isEmpty());
		String text = "float time;\n";
		assertTrue(WordDocs.undeclaredUniforms(List.of(
						"'time' : undeclared identifier"),
				GlslKnowledge.of(text)).isEmpty());
		// A parameter of the first function is no declaration.
		assertEquals("time", only(WordDocs.undeclaredUniforms(List.of(
						"'time' : undeclared identifier"),
				GlslKnowledge.of("float f(float time) { return time; }\n" +
						"void main() { gl_FragColor = vec4(time); }\n"))));
	}

	@Test
	public void fixesAllUndeclaredUniformsOfALineOnceInTheirOrder() {
		List<UniformDef> uniforms = WordDocs.undeclaredUniforms(List.of(
				"'cameraOrientation' : undeclared identifier",
				"'cameraAddent' : undeclared identifier",
				"'cameraOrientation' : undeclared identifier"),
				Knowledge.NONE);
		assertEquals(2, uniforms.size());
		assertEquals("cameraOrientation", uniforms.get(0).getName());
		assertEquals("cameraAddent", uniforms.get(1).getName());
	}

	@Test
	public void joinsWithCommasAndAnd() {
		String and = "%1$s and %2$s";
		assertEquals("", WordDocs.joinAnd(List.of(), and));
		assertEquals("a", WordDocs.joinAnd(List.of("a"), and));
		assertEquals("a and b", WordDocs.joinAnd(List.of("a", "b"), and));
		assertEquals("a, b and c",
				WordDocs.joinAnd(List.of("a", "b", "c"), and));
	}

	private static String only(List<UniformDef> uniforms) {
		assertEquals(1, uniforms.size());
		return uniforms.get(0).getName();
	}

	@Test
	public void writesOwnDeclarationsAsTheSheetShowsThem() {
		String text = "struct Ray { vec3 o, d; float t; };\n" +
				"float f(float x);\n" +
				"float f(vec2 p) { return p.x; }\n" +
				"#define SAT(x) clamp(x, 0.0, 1.0)\n";
		GlslKnowledge known = GlslKnowledge.of(text);
		assertEquals("struct Ray {\n  vec3 o;\n  vec3 d;\n  float t;\n}",
				WordDocs.code(known.at(-1, "Ray")));
		// A function with every form, each in a line.
		assertEquals("float f(float x)\nfloat f(vec2 p)",
				WordDocs.code(known.at(-1, "f")));
		assertEquals("#define SAT(x) clamp(x, 0.0, 1.0)",
				WordDocs.code(known.at(-1, "SAT")));
	}

	// The form the strip shows is chosen by the argument the caret is
	// in: the first with that many parameters.
	@Test
	public void choosesAFormByTheArgumentCount() {
		List<List<String>> forms = List.of(List.of("y_over_x"),
				List.of("y", "x"), List.of("a", "b", "c"));
		assertEquals(List.of("y_over_x"), WordDocs.form(forms, 0));
		assertEquals(List.of("y", "x"), WordDocs.form(forms, 1));
		assertEquals(List.of("a", "b", "c"), WordDocs.form(forms, 2));
		// More arguments than any form takes: the longest.
		assertEquals(List.of("a", "b", "c"), WordDocs.form(forms, 5));
		assertNull(WordDocs.form(List.of(), 0));
	}
}
