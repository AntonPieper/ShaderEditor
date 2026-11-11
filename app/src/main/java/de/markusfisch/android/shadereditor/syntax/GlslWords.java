package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The words the editor colours and completes, each with its style, and
 * the names of preprocessor directives.
 */
public final class GlslWords {
	private static final String[] DIRECTIVES = {
			"define", "elif", "else", "endif", "error", "extension", "if",
			"ifdef", "ifndef", "include", "line", "pragma", "undef",
			"version"};
	private static final String[] WORDS = new String[250];
	private static final Style[] STYLES = new Style[WORDS.length];
	private static int count;

	// The keywords, built-in variables and built-in functions of GLSL ES
	// 1.00 and 3.00 that a fragment shader can use.
	static {
		add(Style.PRIMITIVE, "const", "bool", "float", "int", "uint");
		add(Style.BOOL, "true", "false");
		add(Style.TYPE_VECTOR, "bvec2", "bvec3", "bvec4", "ivec2", "ivec3",
				"ivec4", "uvec2", "uvec3", "uvec4", "vec2", "vec3", "vec4");
		add(Style.TYPE_MATRIX, "mat2", "mat3", "mat4", "mat2x2", "mat2x3",
				"mat2x4", "mat3x2", "mat3x3", "mat3x4", "mat4x2", "mat4x3",
				"mat4x4");
		add(Style.TYPE_QUALIFIER, "attribute", "varying", "centroid", "in",
				"out", "inout", "uniform", "flat", "smooth", "layout");
		add(Style.TYPE_SAMPLER, "sampler2D", "sampler3D", "samplerCube",
				"sampler2DShadow", "samplerCubeShadow", "sampler2DArray",
				"sampler2DArrayShadow", "isampler2D", "isampler3D",
				"isamplerCube", "isampler2DArray", "usampler2D", "usampler3D",
				"usamplerCube", "usampler2DArray", "samplerExternalOES");
		add(Style.KEYWORD, "struct", "void");
		add(Style.CONTROL_FLOW, "while", "break", "continue", "do", "else",
				"for", "if", "discard", "return", "switch", "case", "default");
		add(Style.PRECISION, "invariant", "highp", "mediump", "lowp",
				"precision");
		add(Style.BUILTIN_FUNCTION, "gl_FragCoord", "gl_FrontFacing",
				"gl_FragColor", "gl_FragData", "gl_FragDepth", "gl_PointCoord",
				"gl_DepthRange", "gl_MaxVertexAttribs",
				"gl_MaxVertexUniformVectors", "gl_MaxVaryingVectors",
				"gl_MaxVertexOutputVectors", "gl_MaxFragmentInputVectors",
				"gl_MaxVertexTextureImageUnits",
				"gl_MaxCombinedTextureImageUnits", "gl_MaxTextureImageUnits",
				"gl_MaxFragmentUniformVectors", "gl_MaxDrawBuffers",
				"gl_MinProgramTexelOffset", "gl_MaxProgramTexelOffset");
		add(Style.BUILTIN_FUNCTION, "radians", "degrees", "sin", "cos",
				"tan", "asin", "acos", "atan", "sinh", "cosh", "tanh", "asinh",
				"acosh", "atanh", "pow", "exp", "log", "exp2", "log2", "sqrt",
				"inversesqrt", "abs", "sign", "floor", "trunc", "round",
				"roundEven", "ceil", "fract", "mod", "modf", "min", "max",
				"clamp", "mix", "step", "smoothstep", "isnan", "isinf",
				"floatBitsToInt", "floatBitsToUint", "intBitsToFloat",
				"uintBitsToFloat", "packSnorm2x16", "unpackSnorm2x16",
				"packUnorm2x16", "unpackUnorm2x16", "packHalf2x16",
				"unpackHalf2x16", "length", "distance", "dot", "cross",
				"normalize", "faceforward", "reflect", "refract",
				"matrixCompMult", "outerProduct", "transpose", "determinant",
				"inverse", "lessThan", "lessThanEqual", "greaterThan",
				"greaterThanEqual", "equal", "notEqual", "any", "all", "not",
				"texture2D", "texture2DProj", "textureCube", "textureSize",
				"texture", "textureProj", "textureLod", "textureOffset",
				"texelFetch", "texelFetchOffset", "textureProjOffset",
				"textureLodOffset", "textureProjLod", "textureProjLodOffset",
				"textureGrad", "textureGradOffset", "textureProjGrad",
				"textureProjGradOffset", "dFdx", "dFdy", "fwidth");
	}

	private GlslWords() {
	}

	/**
	 * Style of the word from start to end in text, or
	 * {@link Style#IDENTIFIER} if it is none of the words.
	 */
	public static Style style(CharSequence text, int start, int end) {
		int i = lowerBound(WORDS, count, text, start, end);
		return i < count && compare(WORDS[i], text, start, end) == 0
				? STYLES[i]
				: Style.IDENTIFIER;
	}

	/** True if the word from start to end in text names a directive. */
	public static boolean isDirective(CharSequence text, int start, int end) {
		int i = lowerBound(DIRECTIVES, DIRECTIVES.length, text, start, end);
		return i < DIRECTIVES.length &&
				compare(DIRECTIVES[i], text, start, end) == 0;
	}

	/**
	 * True if the first size of the sorted words contain the word from start
	 * to end in text.
	 */
	static boolean contains(String[] words, int size, CharSequence text,
			int start, int end) {
		int i = lowerBound(words, size, text, start, end);
		return i < size && compare(words[i], text, start, end) == 0;
	}

	/** Words that start with prefix, in sorted order. */
	public static List<String> matching(String prefix) {
		return matching(WORDS, count, prefix);
	}

	/** Directive names that start with prefix, in sorted order. */
	public static List<String> matchingDirectives(String prefix) {
		return matching(DIRECTIVES, DIRECTIVES.length, prefix);
	}

	private static List<String> matching(String[] words, int size,
			String prefix) {
		List<String> result = new ArrayList<>();
		if (prefix.isEmpty()) {
			return result;
		}
		for (int i = lowerBound(words, size, prefix, 0, prefix.length());
				i < size && words[i].startsWith(prefix); ++i) {
			result.add(words[i]);
		}
		return result;
	}

	// Later words replace earlier ones, which keeps WORDS sorted and unique.
	private static void add(Style style, String... words) {
		for (String word : words) {
			int i = Arrays.binarySearch(WORDS, 0, count, word);
			if (i < 0) {
				i = -i - 1;
				System.arraycopy(WORDS, i, WORDS, i + 1, count - i);
				System.arraycopy(STYLES, i, STYLES, i + 1, count - i);
				WORDS[i] = word;
				++count;
			}
			STYLES[i] = style;
		}
	}

	private static int lowerBound(String[] words, int size, CharSequence text,
			int start, int end) {
		int low = 0;
		int high = size;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (compare(words[mid], text, start, end) < 0) {
				low = mid + 1;
			} else {
				high = mid;
			}
		}
		return low;
	}

	private static int compare(String word, CharSequence text, int start,
			int end) {
		int length = Math.min(word.length(), end - start);
		for (int i = 0; i < length; ++i) {
			int d = word.charAt(i) - text.charAt(start + i);
			if (d != 0) {
				return d;
			}
		}
		return word.length() - (end - start);
	}
}
