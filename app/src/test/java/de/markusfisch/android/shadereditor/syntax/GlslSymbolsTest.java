package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslSymbols.Draft;
import de.markusfisch.android.shadereditor.syntax.Knowledge.Symbol;

public class GlslSymbolsTest {
	@Test
	public void aFunctionWithItsParameters() {
		String text = "const vec3  trace(in Ray r, float t[2],\n" +
				"\t\tconst highp float, void)";
		List<Draft> drafts = declared(text);
		assertEquals(Collections.singletonList("FUNCTION trace: " +
						"const vec3 trace(in Ray r, float t[2], " +
						"const highp float, void) [const vec3  trace(in Ray " +
						"r, float t[2],\n\t\tconst highp float, void)]"),
				described(text, drafts));
		assertEquals(Arrays.asList(
						"PARAMETER r: in Ray r [in Ray r]",
						"PARAMETER t: float t[2] [float t[2]]",
						// One without a name is one all the same;
						// "void" alone is none.
						"PARAMETER : const highp float [const highp float]"),
				parts(text, drafts.get(0)));
		// A head alone is one too: its body is not a statement's.
		assertEquals(Collections.singletonList(
						"FUNCTION main: void main( [void main(]"),
				described("void main("));
	}

	@Test
	public void variablesOfOneType() {
		assertEquals(Arrays.asList(
						"UNIFORM a: uniform highp vec3 a [uniform highp vec3 a]",
						"UNIFORM b: uniform highp vec3 b[2] [b[2]]",
						"UNIFORM c: uniform highp vec3 c [c;]"),
				described("uniform highp vec3 a, b[2], c;"));
		assertEquals(Arrays.asList(
						"CONSTANT a: const float a = max(1.0, 2.0) " +
								"[const float a = max(1.0, 2.0)]",
						"CONSTANT b: const float b[2] = float[2](a, a) " +
								"[b[2] = float[2](a, a);]"),
				described("const float a = max(1.0, 2.0), b[2] = " +
						"float[2](a, a);"));
		assertEquals(Collections.singletonList(
						"VARIABLE colour: layout(location = 0) out vec4 colour " +
								"[layout(location = 0) out vec4 colour;]"),
				described("layout(location = 0) out vec4 colour;"));
		assertEquals(Collections.singletonList(
						"VARIABLE r: Ray r [Ray r;]"),
				described("Ray r;"));
	}

	@Test
	public void wordsBeforeTheTypeQualifyIt() {
		// A macro for a precision or a qualifier, and words of other
		// shading languages: the name is the last word, the type the
		// one before it.
		assertEquals(Collections.singletonList("UNIFORM speed: " +
						"uniform PREC float speed [uniform PREC float speed;]"),
				described("uniform PREC float speed;"));
		assertEquals(Collections.singletonList("VARIABLE TEX0: " +
						"COMPAT_VARYING vec4 TEX0 [COMPAT_VARYING vec4 TEX0;]"),
				described("COMPAT_VARYING vec4 TEX0;"));
		assertEquals(Collections.singletonList("CONSTANT x: " +
						"static const float x = 1.0 " +
						"[static const float x = 1.0;]"),
				described("static const float x = 1.0;"));
		assertEquals(Arrays.asList(
						"UNIFORM a: uniform COMPAT_PRECISION float a " +
								"[uniform COMPAT_PRECISION float a]",
						"UNIFORM b: uniform COMPAT_PRECISION float b [b]",
						"UNIFORM c: uniform COMPAT_PRECISION float c [c;]"),
				described("uniform COMPAT_PRECISION float a, b,\n\tc;"));
		assertEquals(Collections.singletonList("VARIABLE rays: " +
						"IN Ray[2] rays [IN Ray[2] rays;]"),
				described("IN Ray[2] rays;"));
		String text = "inline float2 scale(const PREC float2 p, IN float s)";
		List<Draft> drafts = declared(text);
		assertEquals(Collections.singletonList("FUNCTION scale: " + text +
				" [" + text + "]"), described(text, drafts));
		assertEquals(Arrays.asList(
						"PARAMETER p: const PREC float2 p [const PREC float2 p]",
						"PARAMETER s: IN float s [IN float s]"),
				parts(text, drafts.get(0)));
		text = "struct S { PREC float a, b; COMPAT vec2 c; };";
		assertEquals(Arrays.asList(
						"FIELD a: PREC float a [PREC float a]",
						"FIELD b: PREC float b [b]",
						"FIELD c: COMPAT vec2 c [COMPAT vec2 c]"),
				parts(text, declared(text).get(0)));
		// A name still needs a type: a qualifier is none.
		assertEquals(Collections.emptyList(), described("uniform speed;"));
		assertEquals(Collections.emptyList(),
				described("precision highp float;"));
	}

	@Test
	public void theTypeOfWhatIsDeclared() {
		// Whole and without qualifiers, an array with its brackets.
		assertEquals(Arrays.asList("a vec3", "b vec3[2]", "c vec3[N][2]"),
				types(declared("uniform highp vec3 a, b[2], c[N] [2];")));
		assertEquals(Arrays.asList("lights Ray[4]"),
				types(declared("const Ray[4] lights = Ray[4](a, b, c, d);")));
		assertEquals(Arrays.asList("x float"),
				types(declared("static const PREC float x = 1.0;")));
		// A function has what it returns, its parameters their own.
		Draft f = declared("highp vec3 f(in Ray r, const float t[2], " +
				"out mat3, float[2])").get(0);
		assertEquals("vec3", f.type);
		assertEquals(Arrays.asList("r Ray", "t float[2]", " mat3",
				" float[2]"), types(f.parts));
		assertEquals("Ray[2]", declared("Ray[2] pair(vec3 o)").get(0).type);
		assertNull(declared("void main(void)").get(0).parts.isEmpty()
				? null : "parts");
		assertEquals("void", declared("void main(void)").get(0).type);
		// A struct's fields, and what is declared with it.
		List<Draft> drafts = declared(
				"struct Ray { vec3 o, d; float hit[2]; Hit last; } near;");
		assertEquals(Arrays.asList("o vec3", "d vec3", "hit float[2]",
				"last Hit"), types(drafts.get(0).parts));
		assertEquals("Ray", drafts.get(1).type);
		assertNull(declared("struct { float a; } s;").get(0).type);
		// "struct S" and no more is a type without fields yet.
		assertEquals(Collections.singletonList("TYPE S: struct S " +
				"[struct S;]"), described("struct S;"));
	}

	private static List<String> types(List<?> symbols) {
		List<String> list = new ArrayList<>();
		for (Object o : symbols) {
			Symbol symbol = o instanceof Draft ? ((Draft) o).symbol()
					: (Symbol) o;
			list.add(symbol.name() + " " + symbol.type());
		}
		return list;
	}

	@Test
	public void aStructWithItsFields() {
		String text = "struct Ray { vec3 o, d; float t[2]; } near, far;";
		List<Draft> drafts = declared(text);
		assertEquals(Arrays.asList(
						"TYPE Ray: struct Ray " +
								"[struct Ray { vec3 o, d; float t[2]; }]",
						"VARIABLE near: struct Ray near [struct Ray { vec3 o, " +
								"d; float t[2]; } near]",
						"VARIABLE far: struct Ray far [far;]"),
				described(text, drafts));
		assertEquals(Arrays.asList(
						"FIELD o: vec3 o [vec3 o]",
						"FIELD d: vec3 d [d]",
						"FIELD t: float t[2] [float t[2]]"),
				parts(text, drafts.get(0)));
		// Without a name it is no type, but its variable is one.
		assertEquals(Collections.singletonList("VARIABLE s: struct s " +
						"[struct { float a; } s;]"),
				described("struct { float a; } s;"));
		// Without its "}" it has the fields that were written.
		assertEquals(Collections.singletonList("TYPE S: struct S " +
						"[struct S { float a; vec3]"),
				described("struct S { float a; vec3"));
	}

	@Test
	public void aBlock() {
		// Its fields are variables,
		assertEquals(Arrays.asList(
						"UNIFORM a: vec3 a [vec3 a]",
						"UNIFORM b: float b[2] [float b[2]]"),
				described("layout(std140) uniform Block { vec3 a; " +
						"float b[2]; };"));
		// unless one name stands for all of them.
		String text = "uniform Block { vec3 a; float b; } block;";
		List<Draft> drafts = declared(text);
		assertEquals(Collections.singletonList("UNIFORM block: uniform " +
						"Block block [uniform Block { vec3 a; float b; } " +
						"block;]"),
				described(text, drafts));
		assertEquals(Arrays.asList(
						"FIELD a: vec3 a [vec3 a]",
						"FIELD b: float b [float b]"),
				parts(text, drafts.get(0)));
	}

	@Test
	public void whatDeclaresNothing() {
		for (String text : new String[]{
				"", ";", "precision highp float;", "x = 1.0;", "x;",
				"return x;", "float;", "struct;", "uniform;",
				"= 1.0;", "f(x);", "1.0;", ") float x;"}) {
			assertEquals(text, Collections.emptyList(), described(text));
		}
	}

	@Test
	public void onlyTheTokensAskedAbout() {
		GlslStatement statement = GlslStatementTest.statement(
				"float a vec3 f(vec3 p)");
		List<Draft> out = new ArrayList<>();
		GlslSymbols.of(statement, 2, 7, null, out);
		assertEquals(1, out.size());
		assertEquals("a", out.get(0).name);
		assertEquals(0, out.get(0).from);
		assertEquals(7, out.get(0).to);
		out.clear();
		GlslSymbols.of(statement, 0, 0, null, out);
		assertEquals(0, out.size());
		// More than there is means all there is: the last name before
		// the "(" is what the words declare.
		GlslSymbols.of(statement, 99, 22, null, out);
		assertEquals(1, out.size());
		assertEquals("f", out.get(0).name);
	}

	@Test
	public void theCommentBelongsToAllItDeclares() {
		GlslStatement statement = GlslStatementTest.statement(
				"struct S { float a; } s, t");
		List<Draft> out = new ArrayList<>();
		GlslSymbols.of(statement, statement.size(), 26, "Three.", out);
		assertEquals(3, out.size());
		for (Draft draft : out) {
			assertEquals("Three.", draft.documentation);
		}
		out.clear();
		GlslSymbols.of(statement, statement.size(), 26, null, out);
		assertNull(out.get(0).documentation);
	}

	@Test
	public void theSameTokensDeclareTheSame() {
		String text = "uniform Block { vec3 a; float b; } block;";
		assertEquals(described(text), described(text));
		GlslStatement statement = GlslStatementTest.statement(text);
		List<Draft> first = new ArrayList<>();
		List<Draft> second = new ArrayList<>();
		GlslSymbols.of(statement, statement.size(), 40, null, first);
		GlslSymbols.of(statement, statement.size(), 40, null, second);
		assertEquals(first.get(0).symbol(), second.get(0).symbol());
	}

	@Test
	public void aLongDetailIsCut() {
		StringBuilder text = new StringBuilder("float a = 0.0");
		for (int i = 0; i < 200; ++i) {
			text.append(" + 1.0");
		}
		Draft draft = declared(text + ";").get(0);
		assertEquals(240, draft.detail.length());
		assertEquals("float a = 0.0 + 1.0", draft.detail.substring(0, 19));
		assertEquals('…', draft.detail.charAt(239));
	}

	// What text declares, as the reader of declarations asks: the ";"
	// that ends a statement is no token of it.
	private static List<Draft> declared(String text) {
		GlslStatement statement = GlslStatementTest.statement(text);
		int size = statement.size();
		if (size > 0 && statement.is(size - 1, ';')) {
			--size;
		}
		List<Draft> out = new ArrayList<>();
		GlslSymbols.of(statement, size, text.length(), null, out);
		return out;
	}

	private static List<String> described(String text) {
		return described(text, declared(text));
	}

	// Each symbol with how it is written and the text it reaches over.
	private static List<String> described(String text, List<Draft> drafts) {
		List<Symbol> symbols = new ArrayList<>();
		for (Draft draft : drafts) {
			symbols.add(draft.symbol());
		}
		return describedSymbols(text, symbols);
	}

	private static List<String> parts(String text, Draft draft) {
		return describedSymbols(text, draft.parts);
	}

	private static List<String> describedSymbols(String text,
			List<Symbol> symbols) {
		List<String> list = new ArrayList<>();
		for (Symbol symbol : symbols) {
			assertEquals(symbol.name(), text.substring(symbol.start(),
					symbol.end()));
			list.add(symbol.kind() + " " + symbol.name() + ": " +
					symbol.detail() + " [" + text.substring(symbol.from(),
					symbol.to()) + "]");
		}
		return list;
	}
}
