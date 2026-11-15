package de.markusfisch.android.shadereditor.tuning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.markusfisch.android.shadereditor.tuning.ConstantContext.Kind;

// Where GLSL needs a constant, each case is one the offline Mesa
// compiler refuses with a uniform in the number's place, except the
// two of Appendix A of GLSL ES 1.00, which it accepts and a driver
// need not.
public class ConstantContextTest {
	@Test
	public void aConstOrGlobalInitializer() {
		assertEquals(Kind.CONST, es100("void main() { const float k = @2.0; }"));
		assertEquals(Kind.CONST, es300(
				"const vec3 c = vec3(1.0, @0.5, 0.2);"));
		assertEquals(Kind.GLOBAL, es100("float g = @2.0;"));
		assertEquals(Kind.GLOBAL, es100(
				"struct S { float a; } s = S(@1.0);"));
		// A function's body is no initializer of a global.
		assertNull(es100("float f(float x) { return x * @2.0; }"));
		assertNull(es100("void main() { float a = @2.0; }"));
		assertNull(es100("float f(const float x) { float y = x * @2.0; }"));
	}

	@Test
	public void theSizeOfAnArray() {
		assertEquals(Kind.ARRAY, es300("void main() { float a[@3]; }"));
		assertEquals(Kind.ARRAY, es300(
				"void main() { float a[] = float[@3](1.0, 2.0, 3.0); }"));
		assertEquals(Kind.ARRAY, es300("struct S { vec2 p[@4]; };"));
		assertEquals(Kind.ARRAY, es300("struct S { int a; };\n" +
				"void main() { S s[@2]; }"));
		// An index may be a uniform in GLSL ES 3.00, not in ES 1.00.
		assertNull(es300("uniform vec4 u[4]; void main() { vec4 a = u[@2]; }"));
		assertEquals(Kind.ARRAY, es100(
				"uniform vec4 u[4]; void main() { vec4 a = u[@2]; }"));
		assertEquals(Kind.ARRAY, es100(
				"void main() { vec3 v; float a = v[@1]; }"));
	}

	@Test
	public void aLoopHeaderInGlslEs100() {
		String loop = "void main() { for (int i = 0; i < @3; ++i) { } }";
		assertEquals(Kind.LOOP, es100(loop));
		assertNull(es300(loop));
		assertEquals(Kind.LOOP, es100(
				"void main() { for (float x = 0.0; x < 1.0; x += @0.1) {} }"));
		// The body is not the header.
		assertNull(es100(
				"void main() { float s; for (int i = 0; i < 3; ++i) " +
						"{ s += @0.1; } }"));
	}

	@Test
	public void aCaseLabelOrALayout() {
		String cases = "void main() { int x; float c; switch (x) { " +
				"case 1: c = 2.0; break; case %s: c = %s; } }";
		assertEquals(Kind.CASE, es300(String.format(cases, "@2", "1.0")));
		assertNull(es300(String.format(cases, "2", "@1.0")));
		assertEquals(Kind.LAYOUT, es300(
				"layout(location = @0) out vec4 color;"));
	}

	@Test
	public void directivesButNotADefine() {
		assertEquals(Kind.DIRECTIVE, es100("#if FOO > @2\n#endif\n"));
		assertEquals(Kind.DIRECTIVE, es100("#line @10\n"));
		assertEquals(Kind.DIRECTIVE, es100(
				"#pragma se input \"a.frag\" scale @0.5\n"));
		// F3: the app reads FTIME_PERIOD from the text itself.
		assertEquals(Kind.HOST, es100("#define FTIME_PERIOD @7.0\n"));
		assertEquals(Kind.HOST, es100("# define  FTIME_PERIOD \\\n@7.0\n"));
		// A macro may be used anywhere: the compiler tells.
		assertNull(es100("#define SPEED @2.0\nvoid main() { }"));
		assertNull(es100("#define N 3\nvoid main() { float a = @1.0; }"));
	}

	@Test
	public void commentsAreNotCode() {
		assertNull(es100("/* const float k = */ void main() { " +
				"float a = @2.0; }"));
		assertNull(es100("// float g = 1.0;\nvoid main() { " +
				"float a = @2.0; }"));
	}

	// The number is where @ stands.
	private static Kind es100(String code) {
		return of(code, true);
	}

	private static Kind es300(String code) {
		return of(code, false);
	}

	private static Kind of(String code, boolean es100) {
		int at = code.indexOf('@');
		return ConstantContext.of(code.substring(0, at) +
				code.substring(at + 1), at, es100);
	}
}
