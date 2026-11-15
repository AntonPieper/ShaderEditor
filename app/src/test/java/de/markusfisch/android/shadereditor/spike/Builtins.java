package de.markusfisch.android.shadereditor.spike;

import java.util.Arrays;

import de.markusfisch.android.shadereditor.spike.Types.Type;

/**
 * SPIKE, throwaway: the built-in functions of GLSL ES 1.00 and 3.00 a
 * fragment shader can call, as one table of strings: name, one line of
 * documentation, and the signatures with generic types as the
 * specification writes them. A row is parsed the first time its
 * function is looked up. The wording is a placeholder.
 */
final class Builtins {
	// name|one line|signature;signature...
	static final String[] TABLE = {
			"radians|Degrees to radians.|genType radians(genType degrees)",
			"degrees|Radians to degrees.|genType degrees(genType radians)",
			"sin|Sine of an angle in radians.|genType sin(genType angle)",
			"cos|Cosine of an angle in radians.|genType cos(genType angle)",
			"tan|Tangent of an angle in radians.|genType tan(genType angle)",
			"asin|Arc sine, in -PI/2..PI/2.|genType asin(genType x)",
			"acos|Arc cosine, in 0..PI.|genType acos(genType x)",
			"atan|Arc tangent; with two arguments the angle of (x, y)." +
					"|genType atan(genType y, genType x);" +
					"genType atan(genType y_over_x)",
			"sinh|Hyperbolic sine.|genType sinh(genType x)",
			"cosh|Hyperbolic cosine.|genType cosh(genType x)",
			"tanh|Hyperbolic tangent.|genType tanh(genType x)",
			"asinh|Inverse hyperbolic sine.|genType asinh(genType x)",
			"acosh|Inverse hyperbolic cosine.|genType acosh(genType x)",
			"atanh|Inverse hyperbolic tangent.|genType atanh(genType x)",
			"pow|x raised to the power y.|genType pow(genType x, genType y)",
			"exp|e raised to the power x.|genType exp(genType x)",
			"log|Natural logarithm.|genType log(genType x)",
			"exp2|2 raised to the power x.|genType exp2(genType x)",
			"log2|Logarithm to base 2.|genType log2(genType x)",
			"sqrt|Square root.|genType sqrt(genType x)",
			"inversesqrt|One over the square root.|genType inversesqrt(genType x)",
			"abs|Absolute value.|genType abs(genType x);genIType abs(genIType x)",
			"sign|-1, 0 or 1 by the sign of x.|genType sign(genType x);" +
					"genIType sign(genIType x)",
			"floor|Nearest whole number not above x.|genType floor(genType x)",
			"trunc|Whole part of x.|genType trunc(genType x)",
			"round|Nearest whole number.|genType round(genType x)",
			"roundEven|Nearest whole number, halves to even.|genType roundEven(genType x)",
			"ceil|Nearest whole number not below x.|genType ceil(genType x)",
			"fract|x minus floor(x).|genType fract(genType x)",
			"mod|x minus y times floor(x/y).|genType mod(genType x, float y);" +
					"genType mod(genType x, genType y)",
			"modf|Fraction of x; the whole part goes to i.|genType modf(genType x, out genType i)",
			"min|The smaller of x and y.|genType min(genType x, genType y);" +
					"genType min(genType x, float y);" +
					"genIType min(genIType x, genIType y);" +
					"genIType min(genIType x, int y);" +
					"genUType min(genUType x, genUType y);" +
					"genUType min(genUType x, uint y)",
			"max|The larger of x and y.|genType max(genType x, genType y);" +
					"genType max(genType x, float y);" +
					"genIType max(genIType x, genIType y);" +
					"genIType max(genIType x, int y);" +
					"genUType max(genUType x, genUType y);" +
					"genUType max(genUType x, uint y)",
			"clamp|x kept between minVal and maxVal." +
					"|genType clamp(genType x, genType minVal, genType maxVal);" +
					"genType clamp(genType x, float minVal, float maxVal);" +
					"genIType clamp(genIType x, genIType minVal, genIType maxVal);" +
					"genIType clamp(genIType x, int minVal, int maxVal);" +
					"genUType clamp(genUType x, genUType minVal, genUType maxVal);" +
					"genUType clamp(genUType x, uint minVal, uint maxVal)",
			"mix|Blend of x and y: x*(1-a) + y*a." +
					"|genType mix(genType x, genType y, genType a);" +
					"genType mix(genType x, genType y, float a);" +
					"genType mix(genType x, genType y, genBType a)",
			"step|0 below edge, 1 from edge on.|genType step(genType edge, genType x);" +
					"genType step(float edge, genType x)",
			"smoothstep|Smooth step from 0 at edge0 to 1 at edge1." +
					"|genType smoothstep(genType edge0, genType edge1, genType x);" +
					"genType smoothstep(float edge0, float edge1, genType x)",
			"isnan|Whether x is not a number.|genBType isnan(genType x)",
			"isinf|Whether x is infinite.|genBType isinf(genType x)",
			"floatBitsToInt|The bits of a float as an int.|genIType floatBitsToInt(genType value)",
			"floatBitsToUint|The bits of a float as a uint.|genUType floatBitsToUint(genType value)",
			"intBitsToFloat|The bits of an int as a float.|genType intBitsToFloat(genIType value)",
			"uintBitsToFloat|The bits of a uint as a float.|genType uintBitsToFloat(genUType value)",
			"packSnorm2x16|Two floats in -1..1 packed into 32 bits.|uint packSnorm2x16(vec2 v)",
			"unpackSnorm2x16|Unpacks packSnorm2x16.|vec2 unpackSnorm2x16(uint p)",
			"packUnorm2x16|Two floats in 0..1 packed into 32 bits.|uint packUnorm2x16(vec2 v)",
			"unpackUnorm2x16|Unpacks packUnorm2x16.|vec2 unpackUnorm2x16(uint p)",
			"packHalf2x16|Two floats packed as half floats.|uint packHalf2x16(vec2 v)",
			"unpackHalf2x16|Unpacks packHalf2x16.|vec2 unpackHalf2x16(uint v)",
			"length|Length of a vector.|float length(genType x)",
			"distance|Distance between two points.|float distance(genType p0, genType p1)",
			"dot|Dot product.|float dot(genType x, genType y)",
			"cross|Cross product.|vec3 cross(vec3 x, vec3 y)",
			"normalize|The vector with length 1.|genType normalize(genType x)",
			"faceforward|N turned to face against I.|genType faceforward(genType N, genType I, genType Nref)",
			"reflect|I reflected at the normal N.|genType reflect(genType I, genType N)",
			"refract|I refracted at the normal N with ratio eta.|genType refract(genType I, genType N, float eta)",
			"matrixCompMult|Component-wise product of two matrices.|mat matrixCompMult(mat x, mat y)",
			"outerProduct|Column vector c times row vector r.|mat2 outerProduct(vec2 c, vec2 r);" +
					"mat3 outerProduct(vec3 c, vec3 r);mat4 outerProduct(vec4 c, vec4 r)",
			"transpose|Rows and columns exchanged.|mat transpose(mat m)",
			"determinant|Determinant of a matrix.|float determinant(mat m)",
			"inverse|Inverse of a matrix.|mat inverse(mat m)",
			"lessThan|Component-wise x < y.|bvec lessThan(vec x, vec y);" +
					"bvec lessThan(ivec x, ivec y);bvec lessThan(uvec x, uvec y)",
			"lessThanEqual|Component-wise x <= y.|bvec lessThanEqual(vec x, vec y);" +
					"bvec lessThanEqual(ivec x, ivec y);bvec lessThanEqual(uvec x, uvec y)",
			"greaterThan|Component-wise x > y.|bvec greaterThan(vec x, vec y);" +
					"bvec greaterThan(ivec x, ivec y);bvec greaterThan(uvec x, uvec y)",
			"greaterThanEqual|Component-wise x >= y.|bvec greaterThanEqual(vec x, vec y);" +
					"bvec greaterThanEqual(ivec x, ivec y);bvec greaterThanEqual(uvec x, uvec y)",
			"equal|Component-wise x == y.|bvec equal(vec x, vec y);" +
					"bvec equal(ivec x, ivec y);bvec equal(uvec x, uvec y);" +
					"bvec equal(bvec x, bvec y)",
			"notEqual|Component-wise x != y.|bvec notEqual(vec x, vec y);" +
					"bvec notEqual(ivec x, ivec y);bvec notEqual(uvec x, uvec y);" +
					"bvec notEqual(bvec x, bvec y)",
			"any|Whether any component is true.|bool any(bvec x)",
			"all|Whether all components are true.|bool all(bvec x)",
			"not|Component-wise logical not.|bvec not(bvec x)",
			"texture2D|Reads a 2D texture at P (GLSL ES 1.00)." +
					"|vec4 texture2D(sampler2D sampler, vec2 P);" +
					"vec4 texture2D(sampler2D sampler, vec2 P, float bias);" +
					"vec4 texture2D(samplerExternalOES sampler, vec2 P)",
			"texture2DProj|Reads a 2D texture with projection (GLSL ES 1.00)." +
					"|vec4 texture2DProj(sampler2D sampler, vec3 P);" +
					"vec4 texture2DProj(sampler2D sampler, vec4 P);" +
					"vec4 texture2DProj(sampler2D sampler, vec3 P, float bias);" +
					"vec4 texture2DProj(sampler2D sampler, vec4 P, float bias)",
			"textureCube|Reads a cube map in direction P (GLSL ES 1.00)." +
					"|vec4 textureCube(samplerCube sampler, vec3 P);" +
					"vec4 textureCube(samplerCube sampler, vec3 P, float bias)",
			"textureSize|Size of a texture level in pixels." +
					"|ivec2 textureSize(gsampler2D sampler, int lod);" +
					"ivec3 textureSize(gsampler3D sampler, int lod);" +
					"ivec2 textureSize(gsamplerCube sampler, int lod);" +
					"ivec2 textureSize(sampler2DShadow sampler, int lod);" +
					"ivec2 textureSize(samplerCubeShadow sampler, int lod);" +
					"ivec3 textureSize(gsampler2DArray sampler, int lod);" +
					"ivec3 textureSize(sampler2DArrayShadow sampler, int lod)",
			"texture|Reads a texture at P." +
					"|gvec4 texture(gsampler2D sampler, vec2 P);" +
					"gvec4 texture(gsampler2D sampler, vec2 P, float bias);" +
					"gvec4 texture(gsampler3D sampler, vec3 P);" +
					"gvec4 texture(gsampler3D sampler, vec3 P, float bias);" +
					"gvec4 texture(gsamplerCube sampler, vec3 P);" +
					"gvec4 texture(gsamplerCube sampler, vec3 P, float bias);" +
					"float texture(sampler2DShadow sampler, vec3 P);" +
					"float texture(sampler2DShadow sampler, vec3 P, float bias);" +
					"float texture(samplerCubeShadow sampler, vec4 P);" +
					"gvec4 texture(gsampler2DArray sampler, vec3 P);" +
					"gvec4 texture(gsampler2DArray sampler, vec3 P, float bias);" +
					"float texture(sampler2DArrayShadow sampler, vec4 P);" +
					"vec4 texture(samplerExternalOES sampler, vec2 P)",
			"textureProj|Reads a texture with projection." +
					"|gvec4 textureProj(gsampler2D sampler, vec3 P);" +
					"gvec4 textureProj(gsampler2D sampler, vec4 P);" +
					"gvec4 textureProj(gsampler3D sampler, vec4 P);" +
					"float textureProj(sampler2DShadow sampler, vec4 P)",
			"textureLod|Reads a texture at an explicit level of detail." +
					"|gvec4 textureLod(gsampler2D sampler, vec2 P, float lod);" +
					"gvec4 textureLod(gsampler3D sampler, vec3 P, float lod);" +
					"gvec4 textureLod(gsamplerCube sampler, vec3 P, float lod);" +
					"float textureLod(sampler2DShadow sampler, vec3 P, float lod);" +
					"gvec4 textureLod(gsampler2DArray sampler, vec3 P, float lod)",
			"textureOffset|Reads a texture at P moved by whole texels." +
					"|gvec4 textureOffset(gsampler2D sampler, vec2 P, ivec2 offset);" +
					"gvec4 textureOffset(gsampler3D sampler, vec3 P, ivec3 offset);" +
					"float textureOffset(sampler2DShadow sampler, vec3 P, ivec2 offset);" +
					"gvec4 textureOffset(gsampler2DArray sampler, vec3 P, ivec2 offset)",
			"texelFetch|Reads one texel by its whole-number position." +
					"|gvec4 texelFetch(gsampler2D sampler, ivec2 P, int lod);" +
					"gvec4 texelFetch(gsampler3D sampler, ivec3 P, int lod);" +
					"gvec4 texelFetch(gsampler2DArray sampler, ivec3 P, int lod)",
			"texelFetchOffset|texelFetch moved by whole texels." +
					"|gvec4 texelFetchOffset(gsampler2D sampler, ivec2 P, int lod, ivec2 offset);" +
					"gvec4 texelFetchOffset(gsampler3D sampler, ivec3 P, int lod, ivec3 offset);" +
					"gvec4 texelFetchOffset(gsampler2DArray sampler, ivec3 P, int lod, ivec2 offset)",
			"textureProjOffset|textureProj moved by whole texels." +
					"|gvec4 textureProjOffset(gsampler2D sampler, vec3 P, ivec2 offset);" +
					"gvec4 textureProjOffset(gsampler2D sampler, vec4 P, ivec2 offset);" +
					"gvec4 textureProjOffset(gsampler3D sampler, vec4 P, ivec3 offset);" +
					"float textureProjOffset(sampler2DShadow sampler, vec4 P, ivec2 offset)",
			"textureLodOffset|textureLod moved by whole texels." +
					"|gvec4 textureLodOffset(gsampler2D sampler, vec2 P, float lod, ivec2 offset);" +
					"gvec4 textureLodOffset(gsampler3D sampler, vec3 P, float lod, ivec3 offset);" +
					"float textureLodOffset(sampler2DShadow sampler, vec3 P, float lod, ivec2 offset);" +
					"gvec4 textureLodOffset(gsampler2DArray sampler, vec3 P, float lod, ivec2 offset)",
			"textureProjLod|textureProj at an explicit level of detail." +
					"|gvec4 textureProjLod(gsampler2D sampler, vec3 P, float lod);" +
					"gvec4 textureProjLod(gsampler2D sampler, vec4 P, float lod);" +
					"gvec4 textureProjLod(gsampler3D sampler, vec4 P, float lod);" +
					"float textureProjLod(sampler2DShadow sampler, vec4 P, float lod)",
			"textureProjLodOffset|textureProjLod moved by whole texels." +
					"|gvec4 textureProjLodOffset(gsampler2D sampler, vec3 P, float lod, ivec2 offset);" +
					"gvec4 textureProjLodOffset(gsampler2D sampler, vec4 P, float lod, ivec2 offset);" +
					"gvec4 textureProjLodOffset(gsampler3D sampler, vec4 P, float lod, ivec3 offset);" +
					"float textureProjLodOffset(sampler2DShadow sampler, vec4 P, float lod, ivec2 offset)",
			"textureGrad|Reads a texture with explicit gradients." +
					"|gvec4 textureGrad(gsampler2D sampler, vec2 P, vec2 dPdx, vec2 dPdy);" +
					"gvec4 textureGrad(gsampler3D sampler, vec3 P, vec3 dPdx, vec3 dPdy);" +
					"gvec4 textureGrad(gsamplerCube sampler, vec3 P, vec3 dPdx, vec3 dPdy);" +
					"float textureGrad(sampler2DShadow sampler, vec3 P, vec2 dPdx, vec2 dPdy);" +
					"float textureGrad(samplerCubeShadow sampler, vec4 P, vec3 dPdx, vec3 dPdy);" +
					"gvec4 textureGrad(gsampler2DArray sampler, vec3 P, vec2 dPdx, vec2 dPdy);" +
					"float textureGrad(sampler2DArrayShadow sampler, vec4 P, vec2 dPdx, vec2 dPdy)",
			"textureGradOffset|textureGrad moved by whole texels." +
					"|gvec4 textureGradOffset(gsampler2D sampler, vec2 P, vec2 dPdx, vec2 dPdy, ivec2 offset);" +
					"gvec4 textureGradOffset(gsampler3D sampler, vec3 P, vec3 dPdx, vec3 dPdy, ivec3 offset);" +
					"float textureGradOffset(sampler2DShadow sampler, vec3 P, vec2 dPdx, vec2 dPdy, ivec2 offset);" +
					"gvec4 textureGradOffset(gsampler2DArray sampler, vec3 P, vec2 dPdx, vec2 dPdy, ivec2 offset);" +
					"float textureGradOffset(sampler2DArrayShadow sampler, vec4 P, vec2 dPdx, vec2 dPdy, ivec2 offset)",
			"textureProjGrad|textureProj with explicit gradients." +
					"|gvec4 textureProjGrad(gsampler2D sampler, vec3 P, vec2 dPdx, vec2 dPdy);" +
					"gvec4 textureProjGrad(gsampler2D sampler, vec4 P, vec2 dPdx, vec2 dPdy);" +
					"gvec4 textureProjGrad(gsampler3D sampler, vec4 P, vec3 dPdx, vec3 dPdy);" +
					"float textureProjGrad(sampler2DShadow sampler, vec4 P, vec2 dPdx, vec2 dPdy)",
			"textureProjGradOffset|textureProjGrad moved by whole texels." +
					"|gvec4 textureProjGradOffset(gsampler2D sampler, vec3 P, vec2 dPdx, vec2 dPdy, ivec2 offset);" +
					"gvec4 textureProjGradOffset(gsampler2D sampler, vec4 P, vec2 dPdx, vec2 dPdy, ivec2 offset);" +
					"gvec4 textureProjGradOffset(gsampler3D sampler, vec4 P, vec3 dPdx, vec3 dPdy, ivec3 offset);" +
					"float textureProjGradOffset(sampler2DShadow sampler, vec4 P, vec2 dPdx, vec2 dPdy, ivec2 offset)",
			"dFdx|Change of p per pixel to the right.|genType dFdx(genType p)",
			"dFdy|Change of p per pixel upwards.|genType dFdy(genType p)",
			"fwidth|abs(dFdx(p)) + abs(dFdy(p)).|genType fwidth(genType p)",
	};

	// Built-in variables: name, type.
	static final String[] VARIABLES = {
			"gl_FragCoord", "vec4", "gl_FrontFacing", "bool",
			"gl_FragColor", "vec4", "gl_FragDepth", "float",
			"gl_PointCoord", "vec2",
	};

	static final int CONCRETE = 0;
	static final int GEN = 1;
	static final int VEC = 2;
	static final int MAT = 3;
	static final int GSAMPLER = 4;
	static final int GVEC4 = 5;

	/** A type as a signature writes it. */
	static final class Spec {
		final String text;
		final String param;
		final int generic;
		final int base;
		final Type concrete;
		final String suffix;

		Spec(String text, String param) {
			this.text = text;
			this.param = param;
			String s = text;
			Type t = Types.named(s);
			int g = CONCRETE;
			int b = 0;
			String suffix = null;
			if (t == null) {
				switch (s) {
					case "genType" -> g = GEN;
					case "genIType" -> {
						g = GEN;
						b = Types.INT;
					}
					case "genUType" -> {
						g = GEN;
						b = Types.UINT;
					}
					case "genBType" -> {
						g = GEN;
						b = Types.BOOL;
					}
					case "vec" -> g = VEC;
					case "ivec" -> {
						g = VEC;
						b = Types.INT;
					}
					case "uvec" -> {
						g = VEC;
						b = Types.UINT;
					}
					case "bvec" -> {
						g = VEC;
						b = Types.BOOL;
					}
					case "mat" -> g = MAT;
					case "gvec4" -> g = GVEC4;
					default -> {
						if (!s.startsWith("gsampler")) {
							throw new AssertionError("type " + s);
						}
						g = GSAMPLER;
						suffix = s.substring(1);
					}
				}
			}
			generic = g;
			base = b;
			concrete = t;
			this.suffix = suffix;
		}
	}

	static final class Signature {
		final String text;
		final Spec result;
		final Spec[] params;

		Signature(String text) {
			this.text = text;
			int open = text.indexOf('(');
			int space = text.lastIndexOf(' ', open);
			result = new Spec(text.substring(0, space), null);
			String inner = text.substring(open + 1, text.length() - 1).trim();
			String[] parts = inner.isEmpty() ? new String[0]
					: inner.split(",");
			params = new Spec[parts.length];
			for (int i = 0; i < parts.length; ++i) {
				String p = parts[i].trim();
				if (p.startsWith("out ")) {
					p = p.substring(4);
				}
				int sp = p.indexOf(' ');
				params[i] = new Spec(p.substring(0, sp), p.substring(sp + 1));
			}
		}

		/**
		 * Return what a call with these argument types gives, or null
		 * if they do not fit; an unknown argument fits anything. With
		 * prefix, fewer arguments than parameters fit as well.
		 */
		Type apply(Type[] args, int count, boolean prefix) {
			if (prefix ? count > params.length : count != params.length) {
				return null;
			}
			int gen = -1;
			int vec = -1;
			int mat = -1;
			int sampler = -1;
			for (int i = 0; i < count; ++i) {
				Type a = args[i];
				Spec p = params[i];
				if (a == null || a.kind == Types.UNKNOWN) {
					continue;
				}
				switch (p.generic) {
					case CONCRETE:
						if (a != p.concrete) {
							return null;
						}
						break;
					case GEN:
						if ((a.kind != Types.SCALAR &&
								a.kind != Types.VECTOR) || a.base != p.base ||
								(gen >= 0 && gen != a.size)) {
							return null;
						}
						gen = a.size;
						break;
					case VEC:
						if (a.kind != Types.VECTOR || a.base != p.base ||
								(vec >= 0 && vec != a.size)) {
							return null;
						}
						vec = a.size;
						break;
					case MAT:
						if (a.kind != Types.MATRIX || a.size != a.rows ||
								(mat >= 0 && mat != a.size)) {
							return null;
						}
						mat = a.size;
						break;
					default:
						if (a.kind != Types.SAMPLER ||
								!a.name.endsWith(p.suffix) ||
								a.name.length() - p.suffix.length() > 1) {
							return null;
						}
						sampler = a.base;
						break;
				}
			}
			switch (result.generic) {
				case CONCRETE:
					return result.concrete;
				case GEN:
					return gen < 0 ? Types.T_UNKNOWN
							: Types.vector(result.base, gen);
				case VEC:
					return vec < 0 ? Types.T_UNKNOWN
							: Types.vector(result.base, vec);
				case MAT:
					return mat < 0 ? Types.T_UNKNOWN
							: Types.MATRICES[mat - 2][mat - 2];
				default:
					return sampler < 0 ? Types.T_UNKNOWN
							: Types.vector(sampler, 4);
			}
		}
	}

	/** One built-in function with its overloads. */
	static final class Function {
		final String name;
		final String doc;
		final Signature[] signatures;

		Function(String row) {
			int a = row.indexOf('|');
			int b = row.indexOf('|', a + 1);
			name = row.substring(0, a);
			doc = row.substring(a + 1, b);
			String[] parts = row.substring(b + 1).split(";");
			signatures = new Signature[parts.length];
			for (int i = 0; i < parts.length; ++i) {
				signatures[i] = new Signature(parts[i]);
			}
		}
	}

	// The row of a function, by the number Names gives its name, plus 1.
	private static int[] rowOf = new int[512];
	private static final Function[] PARSED = new Function[TABLE.length];
	private static int[] variableOf = new int[512];

	static {
		for (int i = 0; i < TABLE.length; ++i) {
			String row = TABLE[i];
			int id = Names.seed(row.substring(0, row.indexOf('|')));
			rowOf = grow(rowOf, id);
			rowOf[id] = i + 1;
		}
		for (int i = 0; i < VARIABLES.length; i += 2) {
			int id = Names.seed(VARIABLES[i]);
			variableOf = grow(variableOf, id);
			variableOf[id] = i + 1;
		}
	}

	private Builtins() {
	}

	static void touch() {
	}

	private static int[] grow(int[] a, int id) {
		return id < a.length ? a : Arrays.copyOf(a, id * 2);
	}

	/** Return the built-in function whose name has this number, or null. */
	static Function function(int nameId) {
		if (nameId < 0 || nameId >= rowOf.length || rowOf[nameId] == 0) {
			return null;
		}
		int row = rowOf[nameId] - 1;
		Function f = PARSED[row];
		if (f == null) {
			f = new Function(TABLE[row]);
			PARSED[row] = f;
		}
		return f;
	}

	/** Return the type of the built-in variable with this name, or null. */
	static Type variable(int nameId) {
		if (nameId < 0 || nameId >= variableOf.length ||
				variableOf[nameId] == 0) {
			return null;
		}
		return Types.named(VARIABLES[variableOf[nameId]]);
	}

	/** Characters of the table, for sizing. */
	static int tableChars() {
		int n = 0;
		for (String s : TABLE) {
			n += s.length();
		}
		return n;
	}

	static int signatureCount() {
		int n = 0;
		for (String s : TABLE) {
			n += s.substring(s.lastIndexOf('|')).split(";").length;
		}
		return n;
	}
}
