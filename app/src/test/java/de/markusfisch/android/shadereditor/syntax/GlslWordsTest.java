package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Pins the word list: every word with its style, and the completions for
 * every prefix of every word.
 */
public class GlslWordsTest {
	private static final String FIRST_LETTERS =
			"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ_";
	private static final Map<Style, String> WORDS = new EnumMap<>(Style.class);

	static {
		WORDS.put(Style.KEYWORD,
				"struct");
		WORDS.put(Style.PRIMITIVE,
				"bool float int uint void");
		WORDS.put(Style.TYPE_VECTOR,
				"bvec2 bvec3 bvec4 ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 vec2 vec3 " +
				"vec4");
		WORDS.put(Style.TYPE_MATRIX,
				"mat2 mat2x2 mat2x3 mat2x4 mat3 mat3x2 mat3x3 mat3x4 mat4 mat4x2 " +
				"mat4x3 mat4x4");
		WORDS.put(Style.TYPE_SAMPLER,
				"isampler2D isampler2DArray isampler3D isamplerCube sampler2D " +
				"sampler2DArray sampler2DArrayShadow sampler2DShadow sampler3D " +
				"samplerCube samplerCubeShadow samplerExternalOES usampler2D " +
				"usampler2DArray usampler3D usamplerCube");
		WORDS.put(Style.TYPE,
				"gl_DepthRangeParameters");
		WORDS.put(Style.BUILTIN_FUNCTION,
				"abs acos acosh all any asin asinh atan atanh ceil clamp cos cosh " +
				"cross dFdx dFdy degrees determinant distance dot equal exp exp2 " +
				"faceforward floatBitsToInt floatBitsToUint floor fract fwidth " +
				"gl_DepthRange gl_FragColor gl_FragCoord gl_FragData gl_FragDepth " +
				"gl_FrontFacing gl_MaxCombinedTextureImageUnits gl_MaxDrawBuffers " +
				"gl_MaxFragmentInputVectors gl_MaxFragmentUniformVectors " +
				"gl_MaxProgramTexelOffset gl_MaxTextureImageUnits " +
				"gl_MaxVaryingVectors gl_MaxVertexAttribs gl_MaxVertexOutputVectors " +
				"gl_MaxVertexTextureImageUnits gl_MaxVertexUniformVectors " +
				"gl_MinProgramTexelOffset gl_PointCoord greaterThan " +
				"greaterThanEqual intBitsToFloat inverse inversesqrt isinf isnan " +
				"length lessThan lessThanEqual log log2 matrixCompMult max min mix " +
				"mod modf normalize not notEqual outerProduct packHalf2x16 " +
				"packSnorm2x16 packUnorm2x16 pow radians reflect refract round " +
				"roundEven sign sin sinh smoothstep sqrt step tan tanh texelFetch " +
				"texelFetchOffset texture texture2D texture2DProj textureCube " +
				"textureGrad textureGradOffset textureLod textureLodOffset " +
				"textureOffset textureProj textureProjGrad textureProjGradOffset " +
				"textureProjLod textureProjLodOffset textureProjOffset textureSize " +
				"transpose trunc uintBitsToFloat unpackHalf2x16 unpackSnorm2x16 " +
				"unpackUnorm2x16");
		WORDS.put(Style.BOOL,
				"false true");
		WORDS.put(Style.PRECISION,
				"highp lowp mediump precision");
		WORDS.put(Style.CONTROL_FLOW,
				"break case continue default discard do else for if return switch " +
				"while");
		WORDS.put(Style.TYPE_QUALIFIER,
				"attribute centroid const flat in inout invariant layout out " +
				"smooth uniform varying");
	}

	@Test
	public void everyWordWithItsStyle() {
		Map<Style, String> actual = new EnumMap<>(Style.class);
		for (String word : allWords()) {
			actual.merge(GlslWords.style(word, 0, word.length()), word,
					(a, b) -> a + " " + b);
		}
		assertEquals(WORDS, actual);
		assertEquals(188, allWords().size());
	}

	@Test
	public void completionsForEveryPrefix() {
		TreeMap<String, Style> expected = new TreeMap<>();
		for (Map.Entry<Style, String> entry : WORDS.entrySet()) {
			for (String word : entry.getValue().split(" ")) {
				expected.put(word, entry.getKey());
			}
		}
		TreeSet<String> prefixes = new TreeSet<>();
		for (String word : expected.keySet()) {
			for (int i = 1; i <= word.length() + 1; ++i) {
				prefixes.add((word + "x").substring(0, i));
			}
		}
		for (String prefix : prefixes) {
			List<String> matching = new ArrayList<>();
			for (String word : expected.keySet()) {
				if (word.startsWith(prefix)) {
					matching.add(word);
				}
			}
			assertEquals(prefix, matching, GlslWords.matching(prefix));
		}
	}

	@Test
	public void styleOfARangeInsideText() {
		String text = "x float floatx floa";
		assertEquals(Style.PRIMITIVE, GlslWords.style(text, 2, 7));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 8, 14));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 15, 19));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 0, 1));
		assertEquals(Style.IDENTIFIER, GlslWords.style(text, 2, 2));
		assertEquals(Style.CONTROL_FLOW, GlslWords.style("else", 0, 4));
	}

	@Test
	public void directives() {
		for (String name : new String[]{"define", "elif", "else", "endif",
				"error", "extension", "if", "ifdef", "ifndef", "include",
				"line", "pragma", "undef", "version"}) {
			assertTrue(name, GlslWords.isDirective("#" + name + " x", 1,
					1 + name.length()));
		}
		assertFalse(GlslWords.isDirective("def", 0, 3));
		assertFalse(GlslWords.isDirective("defines", 0, 7));
		assertEquals(Arrays.asList("elif", "else", "endif", "error", "extension"),
				GlslWords.matchingDirectives("e"));
	}

	@Test
	public void completeNothing() {
		assertEquals(Collections.emptyList(), GlslWords.matching(""));
		assertEquals(Collections.emptyList(), GlslWords.matchingDirectives(""));
		assertEquals(Collections.emptyList(), GlslWords.matching("xyz"));
		assertEquals(Collections.emptyList(), GlslWords.matching("é"));
	}

	@Test
	public void wordsOfOpenGlEs() {
		assertEquals(Style.BUILTIN_FUNCTION, style("gl_FragColor"));
		assertEquals(Style.BUILTIN_FUNCTION, style("texture2D"));
		assertEquals(Style.BUILTIN_FUNCTION, style("textureCube"));
		assertEquals(Style.BOOL, style("true"));
		assertEquals(Style.BOOL, style("false"));
		assertEquals(Style.TYPE_QUALIFIER, style("attribute"));
		assertEquals(Style.TYPE_QUALIFIER, style("varying"));
		assertEquals(Style.TYPE_SAMPLER, style("samplerExternalOES"));
		assertEquals(Style.TYPE, style("gl_DepthRangeParameters"));
		assertEquals(Style.TYPE_QUALIFIER, style("invariant"));
	}

	@Test
	public void noWordsOfDesktopGlsl() {
		// Neither in GLSL ES 1.00 nor in 3.00: misspelt, desktop only, from
		// a later version, or not for fragment shaders.
		for (String word : new String[]{"samplerbuffer", "samplerBuffer",
				"uimage1DArray", "image2D", "atomic_uint", "double", "dvec2",
				"dmat4", "restrict", "precise", "subroutine", "noise",
				"textureGather", "fma", "gl_Position", "gl_PointSize"}) {
			assertEquals(word, Style.IDENTIFIER, style(word));
		}
	}

	private static Style style(String word) {
		return GlslWords.style(word, 0, word.length());
	}

	private static List<String> allWords() {
		List<String> words = new ArrayList<>();
		for (char c : FIRST_LETTERS.toCharArray()) {
			words.addAll(GlslWords.matching(String.valueOf(c)));
		}
		return words;
	}
}
