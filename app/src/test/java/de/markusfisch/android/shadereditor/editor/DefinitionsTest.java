package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Definitions.Answer;
import de.markusfisch.android.shadereditor.editor.Definitions.Form;

/**
 * The answers of the lexer, and the probes of the reviews of the name
 * actions as they hold for them.
 */
public class DefinitionsTest {
	@Test
	public void jumpsToTheOnePlace() {
		String t = "uniform float speed;\nvoid main() { gl_FragColor = vec4(speed); }\n";
		assertPlaces(t, "speed)", 0, "speed", Form.JUMP, "speed;");
	}

	@Test
	public void foldsAPrototypeIntoItsBodyAndListsOverloads() {
		String t = "float sd(vec2 p, float r);\nfloat sd(vec2 p, float r) { return 1.0; }\n" +
				"float sd(vec3 p, float r) { return 1.0; }\nvoid main() { sd(vec2(0.0), 1.0); }\n";
		Answer a = find(t, "sd(vec2(0", 0, "sd");
		assertEquals(Form.LIST, a.form());
		assertEquals(2, a.places().size());
		String one = "float f(float x);\nvoid main() { f(1.0); }\nfloat f(float y) { return y; }\n";
		assertPlaces(one, "f(1.0)", 0, "f", Form.JUMP, "f(float y)");
	}

	@Test
	public void listsTheLocalsOfOtherFunctionsNearestFirst() {
		String t = "float g(vec2 p) { return p.x; }\nvoid main() { vec2 p = vec2(0.0); g(p); }\n";
		Answer a = find(t, "g(p)", 2, "p");
		assertEquals(Form.LIST, a.form());
		assertEquals(t.indexOf("p = vec2"), a.places().get(0).start());
	}

	@Test
	public void saysNoneOnlyForAWordWrittenNowhereElse() {
		assertEquals(Form.NONE, find("void main() { nope(); }\n", "nope", 0,
				"nope").form());
		assertEquals(Form.UNKNOWN, find("void main() { nope(); nope(); }\n",
				"nope", 0, "nope").form());
		// Not in a comment.
		assertEquals(Form.NONE, find("// nope\nvoid main() { nope(); }\n",
				"nope()", 0, "nope").form());
	}

	@Test
	public void aMacroParameterIsOnItsDefineLine() {
		String t = "#define SAT(x) clamp(x, 0.0, 1.0)\nfloat x;\n";
		assertPlaces(t, "x, 0.0", 0, "x", Form.JUMP, "x) clamp");
	}

	@Test
	public void r1LocalsInIfArmsAreListedNotPicked() {
		String both = "void main() {\n#ifdef A\n\tfloat k = 1.0;\n#else\n\tfloat k = 2.0;\n#endif\n\tgl_FragColor = vec4(k);\n}\n";
		Answer a = find(both, "vec4(k", 5, "k");
		assertEquals(Form.LIST, a.form());
		assertEquals(2, a.places().size());
		String one = "float k = 0.0;\nvoid main() {\n#ifdef A\n\tfloat k = 1.0;\n#endif\n\tgl_FragColor = vec4(k);\n}\n";
		assertEquals(2, find(one, "vec4(k", 5, "k").places().size());
	}

	@Test
	public void r1WhatTheAnalysisCouldNotReadIsRead() {
		String t = "float helper(float x) { return x; }\n// " + "x".repeat(70000) +
				"\nvoid main() { gl_FragColor = vec4(helper(1.0)); }\n";
		assertPlaces(t, "helper(1.0)", 0, "helper", Form.JUMP, "helper(float");
	}

	@Test
	public void r1AMacroNamedLikeABuiltInIsTheFilesName() {
		String t = "#define texture2D texture\nuniform sampler2D s;\nvoid main() { texture2D(s, vec2(0.0)); }\n";
		assertPlaces(t, "texture2D(s", 0, "texture2D", Form.JUMP,
				"texture2D texture");
	}

	@Test
	public void r1GluedNamesAreNeverNone() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "#define MAKE(n) float n##_f() { return 1.0; }\nMAKE(foo)\n" +
				"#include \"lib.glsl\"\nvoid main() {}\n");
		files.put("lib.glsl", "float bar() { return foo_f(); }\n");
		assertEquals(Form.UNKNOWN, findIn(files, "lib.glsl", "foo_f()", 0,
				"foo_f").form());
		// Where the name is written elsewhere, the macro is among the
		// places.
		files.put("lib.glsl", "float foo_f();\nfloat bar() { return foo_f(); }\n");
		Answer a = findIn(files, "lib.glsl", "return foo_f", 7, "foo_f");
		assertEquals(Form.LIST, a.form());
		assertEquals(files.get("main.frag").indexOf("MAKE"), a.places().get(1)
				.start());
	}

	@Test
	public void r1AFormAnIncluderDeclaresIsListed() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "float hash(vec2 p) { return p.x; }\n#include \"lib.glsl\"\nvoid main() {}\n");
		files.put("lib.glsl", "#include \"common.glsl\"\nfloat noise(vec2 p) { return hash(p); }\n");
		files.put("common.glsl", "float hash(float x) { return x; }\n");
		Answer a = findIn(files, "lib.glsl", "hash(p)", 0, "hash");
		assertEquals(Form.LIST, a.form());
		assertEquals(2, a.places().size());
		// The include comes before the includer.
		assertEquals("common.glsl", a.places().get(0).file());
	}

	@Test
	public void r1AFileOrIncludeAnyBuildDidNotReadIsRead() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("other.frag", "#include \"lib.glsl\"\nvoid main() { sdCircle(vec2(0.0), 1.0); }\n");
		files.put("lib.glsl", "float sdCircle(vec2 p, float r) { return length(p) - r; }\n// " +
				"x".repeat(70000) + "\n");
		Answer a = findIn(files, "other.frag", "sdCircle(", 0, "sdCircle");
		assertEquals(Form.JUMP, a.form());
		assertEquals("lib.glsl", a.places().get(0).file());
	}

	@Test
	public void r1FieldsAreNoPlaces() {
		String t = "float r = 1.0;\nstruct Disc { vec2 c; float r; };\n" +
				"void main() { Disc d = Disc(vec2(0.0), r); float x = d.\n\tr; }\n";
		assertPlaces(t, "r);", 0, "r", Form.JUMP, "r = 1.0");
	}

	// ---- The second review's probes ----

	@Test
	public void r2MacrosWhereALocalOrStatementStandsAreNeverJumpedPast() {
		String v3 = "#version 300 es\nprecision mediump float;\nout vec4 color;\n";
		String local = v3 + "#define r(a) mat2(cos(a), sin(a), -sin(a), cos(a))\nvoid main() {\n" +
				"\tvec2 p = vec2(1.0);\n\tfloat r = length(p);\n\tp *= r(0.5);\n}\n";
		assertNotEquals(Form.JUMP, find(local, "r(0.5)", 0, "r").form());
		String param = "#define r(a) mat2(cos(a), sin(a), -sin(a), cos(a))\n" +
				"vec2 f(vec2 p, float r) {\n\treturn r(0.5) * p * r;\n}\n";
		assertNotEquals(Form.JUMP, find(param, "r(0.5)", 0, "r").form());
		String caseLabel = "float k = 0.5;\n#define DECL float k = 2.0\nuniform int i;\n" +
				"void main() {\n\tswitch (i) {\n\tcase 0: DECL;\n\t\tgl_FragColor = vec4(k);\n\t}\n}\n";
		assertNotEquals(Form.JUMP, find(caseLabel, "vec4(k", 5, "k").form());
		String list = "float k = 0.5;\n#define DECL k = 2.0\nvoid main() {\n" +
				"\tfloat y = 1.0, DECL;\n\tgl_FragColor = vec4(k, y, 0.0, 1.0);\n}\n";
		assertNotEquals(Form.JUMP, find(list, "vec4(k", 5, "k").form());
		String brace = v3 + "float k = 0.5;\n#define BEGIN {\n#define END }\nuniform bool c;\n" +
				"void main() {\n\tif (c) BEGIN float k = 2.0; color = vec4(k); END\n}\n";
		assertNotEquals(Form.JUMP, find(brace, "vec4(k", 5, "k").form());
	}

	@Test
	public void r2NamesAMacroMakesAreNeverNone() {
		String local = "#define LOCAL(n) float n = 1.0;\nvoid main() {\n\tLOCAL(k)\n\tgl_FragColor = vec4(k);\n}\n";
		// The k in LOCAL(k) is its declaration.
		assertPlaces(local, "vec4(k", 5, "k", Form.JUMP, "k)\n");
		String name = "#define NAME foo\nfloat NAME(float x) { return x; }\n" +
				"void main() { gl_FragColor = vec4(foo(1.0)); }\n";
		assertEquals(Form.LIST, find(name, "foo(1.0)", 0, "foo").form());
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "#define NAME blurH\n#include \"blur.glsl\"\n#undef NAME\n" +
				"void main() { gl_FragColor = blurH(vec4(1.0)); }\n");
		files.put("blur.glsl", "vec4 NAME(vec4 c) { return c * 0.5; }\n");
		assertEquals(Form.LIST, findIn(files, "main.frag", "blurH(vec4", 0,
				"blurH").form());
	}

	@Test
	public void r2AFileIncludedInAFunctionSeesItsIncludersLocals() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "float k = 0.5;\nvoid main() {\n\tfloat k = 2.0;\n" +
				"#include \"body.glsl\"\n}\n");
		files.put("body.glsl", "float t = k * 2.0;\n");
		Answer a = findIn(files, "body.glsl", "k * 2", 0, "k");
		assertEquals(Form.LIST, a.form());
		assertEquals(2, a.places().size());
		files.put("main.frag", "void main() {\n\tfloat k = 2.0;\n#include \"body.glsl\"\n}\n");
		assertEquals(Form.JUMP, findIn(files, "body.glsl", "k * 2", 0, "k")
				.form());
	}

	// Read as they are now: what was added since any build is seen,
	// what was removed is not.
	@Test
	public void r2TheTextsAreReadAsTheyAreNow() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "#include \"lib/common.glsl\"\n" +
				"void main() { gl_FragColor = vec4(noise2(vec2(1.0)), f(vec3(1.0))); }\n");
		files.put("lib/common.glsl", "float noise2(vec2 p) { return p.x; }\n" +
				"float f(vec2 p) { return p.x; }\nfloat f(vec3 p) { return p.y; }\n");
		assertEquals(Form.JUMP, findIn(files, "main.frag", "noise2(", 0,
				"noise2").form());
		assertEquals(2, findIn(files, "main.frag", "f(vec3", 0, "f").places()
				.size());
		files.put("main.frag", "void main() { gl_FragColor = vec4(noise2(vec2(1.0))); }\n");
		assertEquals(Form.NONE, findIn(files, "main.frag", "noise2(", 0,
				"noise2").form());
	}

	@Test
	public void r2AReturnMacroDoesNotDeclare() {
		String t = "#define RET return\nfloat x; float f(){ RET x; }";
		Answer a = find(t, "RET x", 4, "x");
		for (Definitions.Place p : a.places()) {
			assertTrue(p.start() != t.indexOf("RET x") + 4 ||
					p.kind() != Definitions.Kind.DECLARED);
		}
	}

	// ---- Declarations the lexer cannot see as such ----

	@Test
	public void declarationsAMacroMakesAreListed() {
		for (String body : List.of(
				"#define DECL(t, n) t n = 1.0\nvoid main() {\n\tDECL(float, x);\n\tgl_FragColor = vec4(x);\n}\n",
				"#define ID(t) t\nvoid main() {\n\tID(float) x = 1.0;\n\tgl_FragColor = vec4(x);\n}\n",
				"#define F float\n#define V x\nvoid main() {\n\tF V = 1.0;\n\tgl_FragColor = vec4(x);\n}\n")) {
			String t = "float x = 0.0;\n" + body;
			assertEquals(body, Form.LIST, find(t, "vec4(x", 5, "x").form());
		}
		String glued = "#define CAT(a, b) a##b\nfloat CAT(fo, o)(float v) { return v; }\n" +
				"void main() { gl_FragColor = vec4(foo(1.0)); }\n";
		assertEquals(Form.UNKNOWN, find(glued, "foo(1", 0, "foo").form());
	}

	// ---- Helpers ----

	private static void assertPlaces(String text, String before, int skip,
			String word, Form form, String at) {
		Answer a = find(text, before, skip, word);
		assertEquals(form, a.form());
		assertEquals(text.indexOf(at), a.places().get(0).start());
	}

	private static Answer find(String text, String before, int skip,
			String word) {
		return Definitions.of(List.of(Definitions.index(text, "main.frag")),
				text.indexOf(before) + skip, word);
	}

	private static Answer findIn(Map<String, String> files, String shown,
			String before, int skip, String word) {
		List<Definitions.Index> reach = Definitions.reach(files, shown,
				(file, path) -> {
					Path dir = Path.of(file).getParent();
					return (dir == null ? Path.of(path) : dir.resolve(path))
							.normalize().toString();
				});
		return Definitions.of(new ArrayList<>(reach),
				files.get(shown).indexOf(before) + skip, word);
	}
}
