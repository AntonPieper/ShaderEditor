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
				"struct void");
		WORDS.put(Style.PRIMITIVE,
				"bool const double float int uint");
		WORDS.put(Style.TYPE_VECTOR,
				"bvec2 bvec3 bvec4 ivec2 ivec3 ivec4 uvec2 uvec3 uvec4 vec2 vec3 " +
				"vec4");
		WORDS.put(Style.TYPE_MATRIX,
				"dmat2 dmat2x2 dmat2x3 dmat2x4 dmat3 dmat3x2 dmat3x3 dmat3x4 dmat4 " +
				"dmat4x2 dmat4x3 dmat4x4 dvec2 dvec3 dvec4 mat2 mat2x2 mat2x3 " +
				"mat2x4 mat3 mat3x2 mat3x3 mat3x4 mat4 mat4x2 mat4x3 mat4x4");
		WORDS.put(Style.ATOMIC_COUNTER,
				"atomic_uint");
		WORDS.put(Style.TYPE_SAMPLER,
				"isampler1D isampler1DArray isampler2D isampler2DArray isampler2DMS " +
				"isampler2DMSArray isampler2DRect isampler3D isamplerCube " +
				"isamplerCubeArray isamplerbuffer sampler1D sampler1DArray " +
				"sampler1DArrayShadow sampler1DShadow sampler2D sampler2DArray " +
				"sampler2DArrayShadow sampler2DMS sampler2DMSArray sampler2DRect " +
				"sampler2DRectShadow sampler2DShadow sampler3D samplerCube " +
				"samplerCubeArray samplerCubeArrayShadow samplerCubeShadow " +
				"samplerbuffer usampler1D usampler1DArray usampler2D " +
				"usampler2DArray usampler2DMS usampler2DMSArray usampler2DRect " +
				"usampler3D usamplerCube usamplerCubeArray usamplerbuffer");
		WORDS.put(Style.TYPE_IMAGE,
				"iimage1D iimage1DArray iimage2D iimage2DArray iimage2DMS " +
				"iimage2DMSArray iimage2DRect iimage3D iimageCube iimageCubeArray " +
				"iimagebuffer image1D image1DArray image2D image2DArray image2DMS " +
				"image2DMSArray image2DRect image3D imageCube imageCubeArray " +
				"imagebuffer uimage1D uimage2D uimage2DArray uimage2DMS " +
				"uimage2DMSArray uimage2DRect uimage3D uimageCube uimageCubeArray " +
				"uimagebuffer");
		WORDS.put(Style.BUILTIN_FUNCTION,
				"abs acos acosh all any asin asinh atan atanh atomicAdd atomicAnd " +
				"atomicCompSwap atomicCounter atomicCounterDecrement " +
				"atomicCounterIncrement atomicExchange atomicMax atomicMin atomicOr " +
				"atomicXor barrier bitCount bitfieldExtract bitfieldInsert " +
				"bitfieldReverse ceil clamp cos cosh cross dFdx dFdxCoarse dFdxFine " +
				"dFdy dFdyCoarse dFdyFine degrees determinant distance dot equal " +
				"exp exp2 faceforward findLSB findMSB floatBitsToInt " +
				"floatBitsToUint floor fma fract frexp fwidth fwidthCoarse " +
				"fwidthFine gl_ClipDistance gl_CullDistance gl_FragCoord " +
				"gl_FragDepth gl_FrontFacing gl_GlobalInvocationID " +
				"gl_HelperInvocation gl_InstanceID gl_InvocationID gl_Layer " +
				"gl_LocalInvocationID gl_LocalInvocationIndex gl_NumSamples " +
				"gl_NumWorkGroups gl_PatchVerticesIn gl_PointCoord gl_PointSize " +
				"gl_Position gl_PrimitiveID gl_PrimitiveIDIn gl_SampleID " +
				"gl_SampleMask gl_SampleMaskIn gl_SamplePosition gl_TessCoord " +
				"gl_TessLevelInner gl_TessLevelOuter gl_VertexID gl_ViewportIndex " +
				"gl_WorkGroupID gl_WorkGroupSize greaterThan greaterThanEqual " +
				"groupMemoryBarrier imageAtomicAdd imageAtomicAnd " +
				"imageAtomicCompSwap imageAtomicExchange imageAtomicMax " +
				"imageAtomicMin imageAtomicOr imageAtomicXor imageLoad imageSamples " +
				"imageSize imageStore imulExtended intBitsToFloat " +
				"interpolateAtCentroid interpolateAtOffset interpolateAtSample " +
				"inverse inversesqrt isinf isnan ldexp length lessThan " +
				"lessThanEqual log log2 matrixCompMult max memoryBarrier " +
				"memoryBarrierAtomicCounter memoryBarrierBuffer memoryBarrierImage " +
				"memoryBarrierShared min mix mod modf noise noise1 noise2 noise3 " +
				"noise4 normalize not notEqual outerProduct packDouble2x32 " +
				"packHalf2x16 packSnorm2x16 packSnorm4x8 packUnorm packUnorm2x16 " +
				"packUnorm4x8 pow radians reflect refract round roundEven sign sin " +
				"sinh smoothstep sqrt step tan tanh texelFetch texelFetchOffset " +
				"texture textureGather textureGatherOffset textureGatherOffsets " +
				"textureGrad textureGradOffset textureLod textureLodOffset " +
				"textureOffset textureProj textureProjGrad textureProjGradOffset " +
				"textureProjLod textureProjLodOffset textureProjOffset " +
				"textureQueryLevels textureQueryLod textureSamples textureSize " +
				"transpose trunc uaddCarry uintBitsToFloat umulExtended " +
				"unpackDouble2x32 unpackHalf2x16 unpackSnorm2x16 unpackSnorm4x8 " +
				"unpackUnorm unpackUnorm2x16 unpackUnorm4x8 usubBorrow " +
				"EmitStreamVertex EmitVertex EndPrimitive EndStreamPrimitive");
		WORDS.put(Style.PRECISION,
				"highp invariant lowp mediump precise precision");
		WORDS.put(Style.CONTROL_FLOW,
				"break case continue default discard do else for if return " +
				"subroutine switch while");
		WORDS.put(Style.TYPE_QUALIFIER,
				"buffer centroid coherent flat in inout layout noperspective out " +
				"patch readonly restrict sample shared smooth uniform volatile " +
				"writeonly");
	}

	@Test
	public void everyWordWithItsStyle() {
		Map<Style, String> actual = new EnumMap<>(Style.class);
		for (String word : allWords()) {
			actual.merge(GlslWords.style(word, 0, word.length()), word,
					(a, b) -> a + " " + b);
		}
		assertEquals(WORDS, actual);
		assertEquals(352, allWords().size());
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
				"error", "if", "ifdef", "ifndef", "include", "line", "pragma",
				"undef", "version"}) {
			assertTrue(name, GlslWords.isDirective("#" + name + " x", 1,
					1 + name.length()));
		}
		// Wrong, kept from the old lexer: extension is a directive.
		assertFalse(GlslWords.isDirective("extension", 0, 9));
		assertFalse(GlslWords.isDirective("def", 0, 3));
		assertFalse(GlslWords.isDirective("defines", 0, 7));
		assertEquals(Arrays.asList("elif", "else", "endif", "error"),
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
	public void wordsOfTheOldLexerThatAreWrong() {
		// Kept so that switching lexers changes nothing; corrected later.
		assertEquals(Style.TYPE_SAMPLER, style("samplerbuffer"));
		assertEquals(Style.IDENTIFIER, style("samplerBuffer"));
		assertEquals(Style.IDENTIFIER, style("uimage1DArray"));
		assertEquals(Style.IDENTIFIER, style("gl_FragColor"));
		assertEquals(Style.IDENTIFIER, style("texture2D"));
		assertEquals(Style.IDENTIFIER, style("true"));
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
