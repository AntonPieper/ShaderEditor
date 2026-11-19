package de.markusfisch.android.shadereditor.fragment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.syntax.GlslDocs;
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
		List<WordDocs.Form> forms = List.of(
				form("T atan(T y_over_x)"), form("T atan(T y, T x)"),
				form("float f(float a, float b, float c)"));
		List<String> none = List.of();
		assertEquals(List.of("y_over_x"), WordDocs.form(forms, 0, none));
		assertEquals(List.of("y", "x"), WordDocs.form(forms, 1, none));
		assertEquals(List.of("a", "b", "c"), WordDocs.form(forms, 2, none));
		// More arguments than any form takes: the longest.
		assertEquals(List.of("a", "b", "c"), WordDocs.form(forms, 5, none));
		assertNull(WordDocs.form(List.of(), 0, none));
	}

	// Among the forms with that many parameters, the first that takes
	// the arguments written so far.
	@Test
	public void choosesAFormByTheArgumentTypes() {
		List<WordDocs.Form> mix = List.of(form("T mix(T x, T y, T a)"),
				form("T mix(T x, T y, float a)"),
				form("T mix(T x, T y, B a)"));
		assertEquals("T T T", types(mix, 2, "vec3", "vec3"));
		assertEquals("T T float", types(mix, 2, "vec3", "vec3", "float"));
		assertEquals("T T T", types(mix, 2, "float", "float", "float"));
		assertEquals("T T B", types(mix, 2, "vec3", "vec3", "bvec3"));
		// What is not known fits, and what no form takes shows the
		// first with that many parameters.
		assertEquals("T T T", types(mix, 2, null, "vec3", null));
		assertEquals("T T T", types(mix, 1, "vec3", "vec2"));
		List<WordDocs.Form> own = List.of(form("float sd(vec2 p, float r)"),
				form("float sd(vec3 p, float r)"),
				form("float sd(vec3 p, vec3 size, float round)"));
		assertEquals("vec2 float", types(own, 0));
		assertEquals("vec2 float", types(own, 0, "vec2"));
		assertEquals("vec3 float", types(own, 1, "vec3"));
		assertEquals("vec3 vec3 float", types(own, 1, "vec3", "vec3"));
		assertEquals("vec3 vec3 float", types(own, 2, null, null));
		assertEquals(List.of("float", "vec3"), GlslDocs.parameterTypes(
				"float f(in float a, const highp vec3 b)"));
	}

	private static WordDocs.Form form(String declaration) {
		return new WordDocs.Form(GlslDocs.parameterNames(declaration),
				GlslDocs.parameterTypes(declaration));
	}

	// The types of the form chosen, to tell forms of the same names
	// apart.
	private static String types(List<WordDocs.Form> forms, int argument,
			String... types) {
		List<String> names = WordDocs.form(forms, argument,
				java.util.Arrays.asList(types));
		for (WordDocs.Form form : forms) {
			if (form.names() == names) {
				return String.join(" ", form.types());
			}
		}
		return null;
	}

	@Test
	public void aLocalIsWhatItsFunctionDeclares() {
		String text = "uniform float time;\nfloat speed;\n" +
				"float f(float time) {\n\tvec3 speed = vec3(time);\n" +
				"\treturn speed.x;\n}\n" +
				"void main() {\n\tgl_FragColor = vec4(time * speed);\n}\n";
		Knowledge known = GlslKnowledge.of(text);
		int inF = text.indexOf("return speed") + 7;
		int inMain = text.indexOf("time * speed");
		// In f, both names are its own: a parameter named like one of
		// the app's uniforms, a local named like a global.
		assertTrue(WordDocs.isLocal(known, text.indexOf("vec3(time)") + 5,
				"time"));
		assertTrue(WordDocs.isLocal(known, inF, "speed"));
		assertEquals("vec3", known.at(inF, "speed").get(0).type());
		assertEquals("vec3 speed", known.at(inF, "speed").get(0).detail());
		// Also where they are declared.
		assertTrue(WordDocs.isLocal(known, text.indexOf("time) {"), "time"));
		assertTrue(WordDocs.isLocal(known, text.indexOf("speed = vec3"),
				"speed"));
		// In main they are what the file declares.
		assertFalse(WordDocs.isLocal(known, inMain, "time"));
		assertFalse(WordDocs.isLocal(known, inMain + 7, "speed"));
		assertEquals("float", known.at(inMain + 7, "speed").get(0).type());
		assertFalse(WordDocs.isLocal(known, inMain, "missing"));
		assertFalse(WordDocs.isLocal(Knowledge.NONE, 3, "time"));
	}

	@Test
	public void leavesOutWhatRepeatsTheDefinition() {
		Knowledge.Symbol aa = macro("AA", "#define AA 2");
		assertTrue(WordDocs.repeats(aa, "2"));
		assertTrue(WordDocs.repeats(macro("R", "#define R iResolution.xy"),
				"iResolution.xy"));
		assertFalse(WordDocs.repeats(macro("TAU", "#define TAU (2.0 * PI)"),
				"(2.0 * 3.14159265)"));
		// A call is never its definition.
		assertFalse(WordDocs.repeats(macro("F", "#define F(x) x"), "x"));
		assertFalse(WordDocs.repeats(aa, null));
	}

	private static Knowledge.Symbol macro(String name, String detail) {
		return new Knowledge.Symbol(name, Knowledge.Kind.MACRO, detail, null,
				null, 0, 0, 0, 0, List.of());
	}
}
