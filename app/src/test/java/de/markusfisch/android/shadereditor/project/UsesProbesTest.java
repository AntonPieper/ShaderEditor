package de.markusfisch.android.shadereditor.project;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Use.Why;

/**
 * The cases two independent reviews found uses wrong in, each with the
 * input the review wrote and what it asked for. Where the model has
 * since said that such a program has no answer, or that a place is
 * listed where the review asked only that it is not certain, the test
 * says so. A place that is no use is not among the places at all.
 */
public class UsesProbesTest {
	private static final String MAIN = UsesHarness.MAIN;
	private static final String V3 = "#version 300 es\n";

	// The uses of the first declaration of that name in text.
	private static ProjectKnowledge.Uses uses(String text, String name) {
		return UsesHarness.uses(text, GlslKnowledge.of(text).at(-1, name)
				.get(0).start());
	}

	private static ProjectKnowledge.Place find(ProjectKnowledge.Uses uses,
			String file, int offset) {
		for (ProjectKnowledge.Place place : uses.places()) {
			if (place.file().toString().equals(file) &&
					place.offset() == offset) {
				return place;
			}
		}
		return null;
	}

	private static Why why(ProjectKnowledge.Uses uses, int offset) {
		ProjectKnowledge.Place place = find(uses, MAIN, offset);
		return place == null ? null : place.why();
	}

	private static boolean certain(ProjectKnowledge.Place place) {
		return place != null && place.certain();
	}

	private static void excluded(String text, String name, String marker) {
		assertNull(marker, why(uses(text, name), text.indexOf(marker)));
	}

	// ---- First review: scopes -------------------------------------------

	@Test
	public void initializerAndOrdinaryScopes() {
		String s = "int x; void f(int x){x++;} void main(){ x++; " +
				"{int x=x+1; x++;} x++; int x; x++;}";
		ProjectKnowledge.Uses u = uses(s, "x");
		assertEquals(4, u.places().size());
		assertEquals(Why.USE, why(u, s.indexOf("x+1")));
		assertNull(why(u, s.lastIndexOf("x++")));
	}

	@Test
	public void ordinaryFor() {
		String s = "int x; void f(){for(int x=0;x<2;x++){x++;} x++;}";
		assertEquals(2, uses(s, "x").places().size());
	}

	@Test
	public void forIfElseScope() {
		String s = "int x; void f(){for(int x=0;x<2;x++) if(true) x++; " +
				"else x--; x++;}";
		excluded(s, "x", "x--");
		assertEquals(Why.USE, why(uses(s, "x"), s.lastIndexOf("x++")));
	}

	@Test
	public void conditionalLocalMustNotHideActiveGlobal() {
		String s = "int x; void f(){\n#ifdef MISSING\nint x;\n#else\n" +
				"x++;\n#endif\n}";
		assertEquals(Why.USE, why(uses(s, "x"), s.indexOf("x++")));
	}

	@Test
	public void whileConditionVariable() {
		String s = "bool x; void f(){while(bool x=true){x=false;} " +
				"x=false;}";
		excluded(s, "x", "x=false;");
		assertEquals(Why.USE, why(uses(s, "x"), s.lastIndexOf("x=false")));
	}

	@Test
	public void functionAndStructShadowing() {
		assertEquals(1, uses("float f(); void g(){int f; f++;}", "f")
				.places().size());
		assertEquals(1, uses("struct S{float x;}; void g(){int S; S++;}",
				"S").places().size());
	}

	@Test
	public void fieldIsNotLocal() {
		String s = "struct S{float x;}; void f(){S s; float x; s.x=x;}";
		ProjectKnowledge.Uses u = UsesHarness.uses(s, s.indexOf("x;"));
		assertEquals(2, u.places().size());
		assertEquals(s.indexOf("x=x"), u.places().get(1).offset());
	}

	// ---- First review: the preprocessor ---------------------------------

	@Test
	public void macroOverridesVariableName() {
		String s = "float x;\n#define x 1.0\nvoid f(){float y=x;}";
		excluded(s, "x", "x;}");
	}

	@Test
	public void functionMacroDoesNotReplaceBareVariable() {
		String s = "#define x(a) (a)\nfloat x; void f(){x=1.;}";
		// Asked about the macro, the variable is not certain to be it:
		// the review asked that it is no place; it is listed, since
		// the name of a macro with parameters stands before it, in
		// "float x", and nothing behind such a name is proven. So it
		// is when the variable is asked about.
		assertEquals(Why.IN_MACRO, why(uses(s, "x"), s.indexOf("x=1.")));
		assertEquals(Why.IN_MACRO, why(UsesHarness.uses(s, s.indexOf("x;")),
				s.indexOf("x=1.")));
	}

	@Test
	public void macroPragmaIsNotCode() {
		String s = "#define X 1\n#pragma X\nvoid f(){int a=X;}";
		excluded(s, "X", "X\nvoid");
	}

	@Test
	public void inactiveUndefDoesNotEndMacro() {
		String s = "#define X 1\n#if 0\n#undef X\n#endif\nint a=X;";
		assertEquals(Why.USE, why(uses(s, "X"), s.lastIndexOf("X")));
	}

	@Test
	public void undefChangesConditional() {
		String s = "#define ON\n#undef ON\nfloat x;\n#ifdef ON\n" +
				"float a=x;\n#else\nfloat b=x;\n#endif";
		assertEquals(Why.INACTIVE, why(uses(s, "x"),
				s.indexOf("x;\n#else")));
		assertEquals(Why.USE, why(uses(s, "x"), s.lastIndexOf("x;")));
	}

	@Test
	public void elifZeroIsInactive() {
		String s = "float x;\n#if 0\nfloat a=x;\n#elif 0\nfloat b=x;\n" +
				"#else\nfloat c=x;\n#endif";
		assertEquals(Why.INACTIVE, why(uses(s, "x"),
				s.indexOf("x;\n#else")));
	}

	@Test
	public void commentsInCondition() {
		String s = "float x;\n#if 0 // disabled\nfloat a=x;\n#endif";
		assertEquals(Why.INACTIVE, why(uses(s, "x"), s.lastIndexOf("x;")));
	}

	@Test
	public void macroBodiesParametersAndPasting() {
		String s = "float x;\n#define ID(x) x\n#define CAT(a) x ## a\n" +
				"#define READ x\nvoid f(){x;}";
		ProjectKnowledge.Uses u = uses(s, "x");
		assertNull(why(u, s.indexOf("x)")));
		// Next to "##" it is a piece of a name, not the variable.
		assertNull(why(u, s.indexOf("x ##")));
		assertEquals(Why.IN_MACRO, why(u, s.indexOf("x\nvoid")));
	}

	@Test
	public void unicodeCrLfCommentsAndStrings() {
		String s = "// 😀 x\r\nfloat x;\r\n" +
				"#pragma text \"x\"\r\n/*x*/void f(){x;}";
		// A place is where the name stands in the text as the editor
		// has it, whose line ends are one character.
		String shown = s.replace("\r\n", "\n");
		ProjectKnowledge.Uses u = ProjectKnowledge.read(UsesHarness.files(
				UsesHarness.one(s))).uses(ProjectPath.parse(MAIN).value(),
				UsesHarness.REVISION, UsesHarness.declared(shown,
						shown.indexOf("x;")), null, UsesHarness.BUILTINS);
		assertEquals(2, u.places().size());
		assertEquals(shown.indexOf("x;\n"), u.places().get(0).offset());
		assertEquals(shown.lastIndexOf("x;"), u.places().get(1).offset());
	}

	@Test
	public void continuedMacroBody() {
		String s = V3 + "float x;\n#define READ \\\n x\nvoid f(){x;}";
		assertEquals(Why.IN_MACRO, why(uses(s, "x"), s.indexOf("x\nvoid")));
		// Version 100 has no such line: no answer.
		assertNull(uses(s.substring(V3.length()), "x"));
	}

	@Test
	public void continuedZeroCondition() {
		String s = V3 + "float x;\n#if \\\n0\nfloat a=x;\n#endif";
		assertEquals(Why.INACTIVE, why(uses(s, "x"), s.lastIndexOf("x;")));
	}

	@Test
	public void nestedInactiveBranches() {
		String s = "int x;\n#if 0\n#if 1\nint a=x;\n#endif\n#else\n" +
				"int b=x;\n#endif";
		assertEquals(Why.INACTIVE, why(uses(s, "x"),
				s.indexOf("x;\n#endif")));
		assertEquals(Why.USE, why(uses(s, "x"), s.lastIndexOf("x;")));
	}

	// ---- First review: types --------------------------------------------

	@Test
	public void overloadUsesRectangularProductType() {
		String s = V3 + "float f(vec2 p); float f(vec3 p); " +
				"void g(){mat2x3 m; vec2 v; f(m*v);}";
		// A mat2x3 times a vec2 is a vec3: not the form asked about.
		assertNull(why(uses(s, "f"), s.lastIndexOf("f(")));
	}

	@Test
	public void unknownTextureCannotChooseOverload() {
		String s = V3 + "#define TEX tex\nuniform sampler2DShadow tex; " +
				"float f(float p); float f(vec4 p); " +
				"void g(){f(texture(TEX,vec3(0.)));}";
		ProjectKnowledge.Uses u = UsesHarness.uses(s, s.indexOf("f(vec4"));
		assertNotEquals(Why.USE, why(u, s.lastIndexOf("f(")));
		assertNotNull(why(u, s.lastIndexOf("f(")));
	}

	// ---- First review: files --------------------------------------------

	private static ProjectKnowledge.Uses project(String lib, String main) {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("lib.glsl", lib);
		files.put(MAIN, main);
		return UsesHarness.uses(files, "lib.glsl", GlslKnowledge.of(lib)
				.symbols().get(0).start(), null);
	}

	private static int certainInMain(ProjectKnowledge.Uses uses) {
		int certain = 0;
		for (ProjectKnowledge.Place place : uses.places()) {
			certain += place.certain() && place.file().toString()
					.equals(MAIN) ? 1 : 0;
		}
		return certain;
	}

	@Test
	public void includerRedeclarationIsUncertain() {
		ProjectKnowledge.Uses u = project("float x;",
				"#include \"lib.glsl\"\nfloat x; void f(){x++;}");
		assertEquals(2, u.places().size());
		assertEquals(Why.REDECLARED, u.places().get(1).why());
	}

	@Test
	public void repeatedIncludeDoesNotDuplicatePhysicalPlaces() {
		assertEquals(2, project("#define X 1\n", "#include \"lib.glsl\"\n" +
				"#include \"lib.glsl\"\nint a=X;").places().size());
	}

	@Test
	public void includeCycleHasNoAnswer() {
		// The review asked that it ends; the build refuses such a
		// program, and so does this.
		assertNull(project("#define X 1\n#include \"main.frag\"\n",
				"#include \"lib.glsl\"\nint a=X;"));
	}

	@Test
	public void beforeIncludeCannotMeanIncludedMacro() {
		assertEquals(0, certainInMain(project("#define X 1\n",
				"int X; void f(){X++;}\n#include \"lib.glsl\"\n")));
	}

	@Test
	public void undefOfIncludedMacroStopsItsUses() {
		for (ProjectKnowledge.Place place : project("#define X 1\n",
				"#include \"lib.glsl\"\n#undef X\n" +
						"void f(){float X; X=1.;}\nint a=X;").places()) {
			assertTrue(place.toString(), place.line() < 2);
		}
	}

	@Test
	public void inactiveIncludeDoesNotSupplyCertainMacro() {
		// The build puts the file where its include stands, in lines
		// that are not compiled: the macro is defined nowhere, its
		// "#define" is listed as not compiled and nothing is certain.
		ProjectKnowledge.Uses u = project("#define X 1\n",
				"#if 0\n#include \"lib.glsl\"\n#endif\nint a=X;");
		assertEquals(0, certainInMain(u));
		assertEquals("[lib.glsl:8 INACTIVE (#if 0)]",
				UsesHarness.told(u).toString());
	}

	@Test
	public void includedMacroBeforeItsIncludeIsNotDefined() {
		ProjectKnowledge.Uses u = project("#define X 1\n",
				"#ifdef X\nint a=X;\n#endif\n#include \"lib.glsl\"\n");
		ProjectKnowledge.Place place = find(u, MAIN, 15);
		assertEquals(1, place.line());
		assertEquals(Why.INACTIVE, place.why());
	}

	// ---- Second review --------------------------------------------------
	// A macro passed as an argument, a type as an argument, a macro
	// named like a type, a struct type written with a macro, a define
	// continued before its "(" and the two cases of characters that
	// are no ASCII are rows of UsesTest.

	@Test
	public void macroTypeOfALocalChoosesNoForm() {
		String s = "#define VEC vec2\nfloat f(vec2 p){return p.x;}\n" +
				"float f(vec3 p){return p.x;}\n" +
				"void g(){ VEC v = VEC(1.); f(v); }\n";
		// Listed, not left out.
		ProjectKnowledge.Uses u = UsesHarness.uses(s, s.indexOf("f(vec2"));
		assertEquals(Why.IN_MACRO, why(u, s.lastIndexOf("f(")));
		assertEquals("VEC", find(u, MAIN, s.lastIndexOf("f(")).about());
	}

	@Test
	public void staleSymbolIsRefusedByItsRevision() {
		// The symbol was made of an older text: a global x at offset
		// 15. In the text now a local x of f stands there.
		String now = "void f(){float x; x++;} float x; void g(){ x++; }\n";
		String old = "         float x; void f(){ x++; } void g(){ x++; }\n";
		assertEquals(now.indexOf("x;"), old.indexOf("x;"));
		ProjectKnowledge knowledge = ProjectKnowledge.read(
				UsesHarness.files(UsesHarness.one(now)));
		assertNull(knowledge.uses(ProjectPath.parse(MAIN).value(),
				"an older revision", GlslKnowledge.of(old).at(-1, "x")
						.get(0), null, UsesHarness.BUILTINS));
	}

	@Test
	public void staleFunctionSymbolMixesNoForms() {
		String old = "float f(float a){return a;}\n" +
				"float f(vec2 a){return a.x;}\nvoid g(){ f(1.); " +
				"f(vec2(1.)); }\n";
		String now = "float f(vec2 a){return a.x;}\n" +
				"float f(float a){return a;}\nvoid g(){ f(1.); " +
				"f(vec2(1.)); }\n";
		// Also at the same revision: another form stands there.
		ProjectKnowledge knowledge = ProjectKnowledge.read(
				UsesHarness.files(UsesHarness.one(now)));
		assertNull(knowledge.uses(ProjectPath.parse(MAIN).value(),
				UsesHarness.REVISION, UsesHarness.declared(old,
						old.indexOf("f(float")), null, UsesHarness.BUILTINS));
	}

	@Test
	public void sameFileTwiceWithDifferentDefines() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put(MAIN, "float x;\n#define A\n#include \"lib.glsl\"\n" +
				"#undef A\n#include \"lib.glsl\"\n");
		files.put("lib.glsl", "#ifdef A\nfloat p = x;\n#else\n" +
				"float q = x;\n#endif\n");
		ProjectKnowledge.Uses u = UsesHarness.uses(files, MAIN, 6, null);
		String lib = files.get("lib.glsl");
		// Each x of lib is compiled in one of the two copies and means
		// the global there: never inactive. It is listed all the same,
		// since lib.glsl does not include what declares the name.
		assertEquals(Why.USE, find(u, "lib.glsl", lib.indexOf("x;")).why());
		assertEquals(Why.USE, find(u, "lib.glsl", lib.lastIndexOf("x;"))
				.why());
	}

	@Test
	public void guardedLibraryWithAUseOfItsOwn() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("lib.glsl", "#ifndef LIB\n#define LIB\n" +
				"float helper(float a){return a;}\n" +
				"float twice(float a){return helper(a) * 2.;}\n#endif\n");
		files.put("a.glsl", "#include \"lib.glsl\"\n" +
				"float fa(){return helper(1.);}\n");
		files.put("b.glsl", "#include \"lib.glsl\"\n" +
				"float fb(){return twice(2.);}\n");
		files.put(MAIN, "#include \"a.glsl\"\n#include \"b.glsl\"\n" +
				"void main(){fa();fb();}\n");
		String lib = files.get("lib.glsl");
		ProjectKnowledge.Uses u = UsesHarness.uses(files, "lib.glsl",
				lib.indexOf("helper"), null);
		assertTrue(UsesHarness.told(u).toString(), certain(find(u,
				"lib.glsl", lib.indexOf("helper"))) && certain(find(u,
				"lib.glsl", lib.lastIndexOf("helper"))) && certain(find(u,
				"a.glsl", files.get("a.glsl").indexOf("helper"))));
		// The guard itself: its "#ifndef" stands before the "#define"
		// it guards and is a place of it, listed.
		u = UsesHarness.uses(files, "lib.glsl", lib.indexOf("LIB\nfloat"),
				null);
		assertEquals(UsesHarness.told(u).toString(), Why.TEST, find(u,
				"lib.glsl", lib.indexOf("LIB")).why());
	}

	@Test
	public void callCutByTheTokenCapOfItsLine() {
		StringBuilder line = new StringBuilder(
				"void g(){ vec2 v; f(v); h(1.);");
		for (int i = 0; i < 197; ++i) {
			line.append(" 1.;");
		}
		line.append(" }\n");
		String text = "float h(float a){return a;}\n" +
				"float f(float a){return a;}\nfloat f(vec2 a){return a.x;}\n" +
				line;
		// The call is of the other form, whatever follows in its line.
		assertNull(why(UsesHarness.uses(text, text.indexOf("f(float")),
				text.indexOf("f(v)")));
		assertEquals(Why.USE, why(UsesHarness.uses(text,
				text.indexOf("f(vec2")), text.indexOf("f(v)")));
	}

	@Test
	public void memberCutByTheTokenCap() {
		String head = "struct S { float x; };\nstruct W { float x; };\n" +
				"struct T { W a[2]; };\n";
		for (int k = 180; k < 220; k += 3) {
			String text = head + "void g(){ S a[2]; T t; t.a[0" +
					"+0".repeat(k) + "].x = 1.; }\n";
			assertFalse("W's field as S's at k=" + k, certain(find(
					UsesHarness.uses(text, text.indexOf("x;")), MAIN,
					text.indexOf("].x") + 2)));
		}
	}
}
