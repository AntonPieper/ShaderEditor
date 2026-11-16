package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class GlslTypesTest {
	@Test
	public void numbersAndVectors() {
		assertEquals(1, GlslTypes.components("float"));
		assertEquals(3, GlslTypes.components("vec3"));
		assertEquals(4, GlslTypes.components("ivec4"));
		assertEquals(2, GlslTypes.components("bvec2"));
		for (String other : new String[]{"mat3", "Ray", "vec5", "xvec3",
				"vec", "", "float[2]", "sampler2D", "vec33"}) {
			assertEquals(other, 0, GlslTypes.components(other));
		}
		assertEquals(0, GlslTypes.components(null));
		assertEquals('f', GlslTypes.base("vec3"));
		assertEquals('u', GlslTypes.base("uint"));
		assertEquals('b', GlslTypes.base("bvec4"));
		assertEquals(0, GlslTypes.base("mat2"));
		assertEquals("uvec3", GlslTypes.vector('u', 3));
		assertEquals("bool", GlslTypes.vector('b', 1));
		assertEquals(3, GlslTypes.columns("mat3"));
		assertEquals(2, GlslTypes.columns("mat2x3"));
		assertEquals(0, GlslTypes.columns("material"));
	}

	@Test
	public void theComponentsOfAVector() {
		assertEquals("vec2", GlslTypes.swizzle("vec3", "xy"));
		assertEquals("float", GlslTypes.swizzle("vec2", "y"));
		assertEquals("vec4", GlslTypes.swizzle("vec2", "xyxy"));
		assertEquals("ivec3", GlslTypes.swizzle("ivec4", "bgr"));
		assertEquals("vec2", GlslTypes.swizzle("vec4", "st"));
		// A component the vector has not, names of two sets, more than
		// four, and what is no vector.
		assertNull(GlslTypes.swizzle("vec2", "z"));
		assertNull(GlslTypes.swizzle("vec4", "xg"));
		assertNull(GlslTypes.swizzle("vec4", "xyzwx"));
		assertNull(GlslTypes.swizzle("vec4", "length"));
		assertNull(GlslTypes.swizzle("float", "x"));
		assertNull(GlslTypes.swizzle("Ray", "x"));
		assertNull(GlslTypes.swizzle(null, "x"));
		assertEquals(Arrays.asList("x", "y", "r", "g", "s", "t", "xy"),
				GlslTypes.swizzles("vec2"));
		assertTrue(GlslTypes.swizzles("vec4").containsAll(Arrays.asList(
				"w", "a", "q", "xyz", "rgb")));
		assertEquals(Collections.emptyList(), GlslTypes.swizzles("float"));
		assertEquals(Collections.emptyList(), GlslTypes.swizzles("Ray"));
	}

	@Test
	public void whatAnIndexGives() {
		assertEquals("float", GlslTypes.element("float[2]"));
		assertEquals("vec3[2]", GlslTypes.element("vec3[2][N]"));
		assertEquals("Ray", GlslTypes.element("Ray[]"));
		assertEquals("float", GlslTypes.element("vec3"));
		assertEquals("int", GlslTypes.element("ivec2"));
		assertEquals("vec3", GlslTypes.element("mat3"));
		assertEquals("vec3", GlslTypes.element("mat2x3"));
		assertNull(GlslTypes.element("float"));
		assertNull(GlslTypes.element("Ray"));
		assertNull(GlslTypes.element(null));
	}

	@Test
	public void whatAnOperatorGives() {
		assertEquals("vec3", GlslTypes.arithmetic("vec3", '+', "vec3"));
		assertEquals("vec3", GlslTypes.arithmetic("vec3", '*', "float"));
		assertEquals("vec3", GlslTypes.arithmetic("float", '*', "vec3"));
		assertEquals("ivec2", GlslTypes.arithmetic("ivec2", '%', "int"));
		assertEquals("mat3", GlslTypes.arithmetic("mat3", '*', "float"));
		assertEquals("mat3", GlslTypes.arithmetic("float", '/', "mat3"));
		assertEquals("vec3", GlslTypes.arithmetic("mat3", '*', "vec3"));
		assertEquals("vec3", GlslTypes.arithmetic("vec3", '*', "mat3"));
		assertEquals("mat3", GlslTypes.arithmetic("mat3", '*', "mat3"));
		assertEquals("mat3", GlslTypes.arithmetic("mat3", '+', "mat3"));
		// A matrix that is not square: "mat2x3" has two columns of
		// three rows.
		assertEquals("vec3", GlslTypes.arithmetic("mat2x3", '*', "vec2"));
		assertEquals("vec2", GlslTypes.arithmetic("vec3", '*', "mat2x3"));
		assertEquals("mat4x3", GlslTypes.arithmetic("mat2x3", '*',
				"mat4x2"));
		assertEquals("mat3", GlslTypes.arithmetic("mat2x3", '*', "mat3x2"));
		assertEquals("mat2x3", GlslTypes.arithmetic("mat2x3", '+',
				"mat2x3"));
		// What GLSL does not allow, and what is not known: nothing is
		// converted, and no type is made up.
		for (String[] pair : new String[][]{{"vec3", "vec2"},
				{"mat3", "vec3"}, {"vec3", "int"}, {"float", "int"},
				{"bvec2", "bvec2"}, {"Ray", "Ray"}, {"mat2x3", "mat3x2"},
				{"mat3", "int"}}) {
			assertNull(pair[0], GlslTypes.arithmetic(pair[0], '+', pair[1]));
		}
		assertNull(GlslTypes.arithmetic("mat2x3", '*', "vec3"));
		assertNull(GlslTypes.arithmetic("vec2", '*', "mat2x3"));
		assertNull(GlslTypes.arithmetic("mat2x3", '*', "mat2x3"));
		assertNull(GlslTypes.arithmetic(null, '+', "vec2"));
		assertNull(GlslTypes.arithmetic("vec2", '*', null));
		assertEquals(3, GlslTypes.rows("mat2x3"));
		assertEquals(4, GlslTypes.rows("mat4"));
		assertEquals(0, GlslTypes.rows("vec3"));
	}

	@Test
	public void numbersAsTheyAreWritten() {
		assertEquals("int", GlslTypes.literal("12"));
		assertEquals("int", GlslTypes.literal("0xFF"));
		assertEquals("uint", GlslTypes.literal("7u"));
		assertEquals("float", GlslTypes.literal("1.0"));
		assertEquals("float", GlslTypes.literal(".5"));
		assertEquals("float", GlslTypes.literal("1e-3"));
		assertEquals("float", GlslTypes.literal("2f"));
	}

	@Test
	public void whatABuiltInFunctionReturns() {
		assertEquals("vec3", builtin("normalize", "vec3"));
		assertEquals("vec2", builtin("mix", "vec2", "vec2", "float"));
		assertEquals("vec3", builtin("smoothstep", "float", "float", "vec3"));
		assertEquals("float", builtin("length", "vec3"));
		assertEquals("float", builtin("dot", null, null));
		assertEquals("vec3", builtin("cross", "vec3", "vec3"));
		assertEquals("vec4", builtin("texture2D", "sampler2D", "vec2"));
		assertEquals("ivec4", builtin("texture", "isampler2D", "vec2"));
		assertEquals("float", builtin("texture", "sampler2DShadow", "vec3"));
		assertEquals("bvec3", builtin("lessThan", "vec3", "vec3"));
		assertEquals("bool", builtin("any", "bvec3"));
		assertEquals("ivec2", builtin("textureSize", "sampler2D", "int"));
		assertEquals("mat3x2", builtin("transpose", "mat2x3"));
		assertEquals("mat4", builtin("transpose", "mat4"));
		assertEquals("mat2x3", builtin("outerProduct", "vec3", "vec2"));
		// A sampler whose type is not known tells nothing of what it
		// holds: no likely type is taken for it.
		assertNull(builtin("texture", null, "vec2"));
		assertNull(builtin("texture2D", (String) null));
		assertNull(builtin("transpose", "vec3"));
		// Its own function, and one whose type follows what is not known.
		assertNull(builtin("palette", "float"));
		assertNull(builtin("normalize", (String) null));
		assertNull(builtin("sin"));
	}

	private static String builtin(String name, String... arguments) {
		return GlslTypes.builtin(name, Arrays.asList(arguments));
	}

	@Test
	public void theArgumentsAFunctionTakes() {
		assertTrue(accepts("float vec3", "float", "vec3"));
		assertTrue(accepts("float vec3", "float"));
		assertTrue(accepts("float vec3"));
		assertFalse(accepts("float vec3", "vec3"));
		assertFalse(accepts("float", "float", "float"));
		// What is not known fits.
		assertTrue(accepts("float vec3", null, "vec3"));
		assertFalse(accepts("float vec3", null, "vec2"));
		// One letter stands for one type; B has bools of T's size.
		assertTrue(accepts("T T float", "vec3", "vec3", "float"));
		assertFalse(accepts("T T T", "vec3", "vec3", "float"));
		assertTrue(accepts("T T T", "float", "float", "float"));
		assertTrue(accepts("T T B", "vec3", "vec3", "bvec3"));
		assertFalse(accepts("T T B", "vec3", "vec3", "bvec2"));
		assertFalse(accepts("T T B", "vec3", "vec3", "float"));
	}

	private static boolean accepts(String parameters, String... arguments) {
		List<String> types = parameters.isEmpty() ? Collections.emptyList()
				: Arrays.asList(parameters.split(" "));
		return GlslTypes.accepts(types, Arrays.asList(arguments));
	}
}
