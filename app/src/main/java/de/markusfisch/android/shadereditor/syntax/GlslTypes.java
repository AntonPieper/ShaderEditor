package de.markusfisch.android.shadereditor.syntax;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The types of GLSL ES 1.00 and 3.00 as their names, and what the
 * language says of them: the members of a vector, what an index and an
 * operator give, what a built-in function returns.
 *
 * A type is the text GLSL writes for it without qualifiers, an array
 * with its brackets behind it: "vec3", "float[2]", "Ray". Null is a
 * type that is not known, and whatever is asked of it is not known
 * either; nothing here guesses.
 */
final class GlslTypes {
	private static final String[] SETS = {"xyzw", "rgba", "stpq"};
	// What a built-in function returns: the type of its first argument
	// (a) or its last (l), a float (f), a vec3 (3), what a texture holds
	// (t), a bool (B), bools of the size of its first argument (b), an
	// unsigned number (u), a vec2 (2), sizes in whole numbers (s), its
	// matrix with rows and columns exchanged (x), the matrix of a
	// column and a row (o).
	private static final Map<String, Character> BUILTINS = new HashMap<>();

	static {
		rule('a', "radians", "degrees", "sin", "cos", "tan", "asin", "acos",
				"atan", "sinh", "cosh", "tanh", "asinh", "acosh", "atanh",
				"pow", "exp", "log", "exp2", "log2", "sqrt", "inversesqrt",
				"abs", "sign", "floor", "trunc", "round", "roundEven", "ceil",
				"fract", "mod", "modf", "min", "max", "clamp", "mix",
				"normalize", "faceforward", "reflect", "refract", "dFdx",
				"dFdy", "fwidth", "matrixCompMult", "inverse");
		rule('x', "transpose");
		rule('o', "outerProduct");
		rule('l', "step", "smoothstep");
		rule('f', "length", "distance", "dot", "determinant");
		rule('3', "cross");
		rule('t', "texture", "texture2D", "textureCube", "texture2DProj",
				"textureProj", "textureLod", "texture2DLod", "textureCubeLod",
				"textureGrad", "textureOffset", "texelFetch",
				"texelFetchOffset", "texture2DLodEXT", "textureCubeLodEXT");
		rule('B', "any", "all");
		rule('b', "lessThan", "lessThanEqual", "greaterThan",
				"greaterThanEqual", "equal", "notEqual", "not", "isnan",
				"isinf");
		rule('u', "packSnorm2x16", "packUnorm2x16", "packHalf2x16");
		rule('2', "unpackSnorm2x16", "unpackUnorm2x16", "unpackHalf2x16");
		rule('s', "textureSize");
	}

	// The names of the types GLSL has itself, each once: a text of a
	// thousand floats keeps one "float".
	private static final Map<String, String> NAMES = new HashMap<>();

	static {
		for (String name : new String[]{"void", "float", "int", "uint",
				"bool", "vec2", "vec3", "vec4", "ivec2", "ivec3", "ivec4",
				"uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2",
				"mat3", "mat4", "sampler2D", "samplerCube", "sampler3D"}) {
			NAMES.put(name, name);
		}
	}

	private GlslTypes() {
	}

	/** Return type, as the one text all its uses share if it has one. */
	static String shared(String type) {
		String shared = type == null ? null : NAMES.get(type);
		return shared != null ? shared : type;
	}

	private static void rule(char rule, String... names) {
		for (String name : names) {
			BUILTINS.put(name, rule);
		}
	}

	/**
	 * Return how many numbers a scalar or a vector has, 1 to 4; 0 for
	 * any other type.
	 */
	static int components(String type) {
		if (type == null) {
			return 0;
		}
		switch (type) {
			case "float":
			case "int":
			case "uint":
			case "bool":
				return 1;
			default:
				int at = type.length() - 4;
				if (at < 0 || at > 1 || !type.startsWith("vec", at) ||
						(at == 1 && "iub".indexOf(type.charAt(0)) < 0)) {
					return 0;
				}
				char n = type.charAt(type.length() - 1);
				return n >= '2' && n <= '4' ? n - '0' : 0;
		}
	}

	/**
	 * Return what the numbers of a scalar or vector are: 'f', 'i', 'u'
	 * or 'b'; 0 for any other type.
	 */
	static char base(String type) {
		if (components(type) == 0) {
			return 0;
		}
		char c = type.charAt(0);
		return c == 'v' ? 'f' : c;
	}

	/** Return the scalar or vector of components numbers of base. */
	static String vector(char base, int components) {
		if (components == 1) {
			return base == 'f' ? "float" : base == 'i' ? "int"
					: base == 'u' ? "uint" : "bool";
		}
		return (base == 'f' ? "" : String.valueOf(base)) + "vec" + components;
	}

	/** Return how many columns a matrix has; 0 for any other type. */
	static int columns(String type) {
		return type != null && type.length() >= 4 && type.startsWith("mat") &&
				type.charAt(3) >= '2' && type.charAt(3) <= '4' &&
				(type.length() == 4 || (type.length() == 6 &&
						type.charAt(4) == 'x'))
				? type.charAt(3) - '0' : 0;
	}

	/** Return how many rows a matrix has; 0 for any other type. */
	static int rows(String type) {
		int columns = columns(type);
		return columns == 0 ? 0 : type.length() == 6
				? type.charAt(5) >= '2' && type.charAt(5) <= '4'
				? type.charAt(5) - '0' : 0 : columns;
	}

	/** Return the matrix of so many columns and rows. */
	static String matrix(int columns, int rows) {
		return columns == rows ? "mat" + columns
				: "mat" + columns + "x" + rows;
	}

	/** Return whether type is an array. */
	static boolean isArray(String type) {
		return type != null && type.endsWith("]");
	}

	/**
	 * Return the type of the components name selects of a vector, "xy"
	 * of a vec3 a vec2, or null when type is no vector or has no such
	 * components.
	 */
	static String swizzle(String type, CharSequence name) {
		int size = components(type);
		int length = name.length();
		if (size < 2 || length < 1 || length > 4) {
			return null;
		}
		String set = null;
		for (int i = 0; i < length; ++i) {
			char c = name.charAt(i);
			if (set == null) {
				for (String s : SETS) {
					set = s.indexOf(c) >= 0 ? s : set;
				}
			}
			int index = set == null ? -1 : set.indexOf(c);
			if (index < 0 || index >= size) {
				return null;
			}
		}
		return vector(base(type), length);
	}

	/**
	 * Return what may stand behind a "." after a vector of type: its
	 * components by each of their names, and the usual groups of them;
	 * nothing for any other type.
	 */
	static List<String> swizzles(String type) {
		List<String> out = new ArrayList<>();
		int size = components(type);
		if (size < 2) {
			return out;
		}
		for (String set : SETS) {
			for (int i = 0; i < size; ++i) {
				out.add(String.valueOf(set.charAt(i)));
			}
		}
		out.add("xy");
		if (size > 2) {
			out.add("xyz");
			out.add("rgb");
		}
		return out;
	}

	/**
	 * Return what an index gives: the element of an array, a number of
	 * a vector, a column of a matrix; null for any other type.
	 */
	static String element(String type) {
		if (isArray(type)) {
			return type.substring(0, type.lastIndexOf('['));
		}
		int size = components(type);
		if (size > 1) {
			return vector(base(type), 1);
		}
		int columns = columns(type);
		if (columns > 0) {
			// The rows of "mat2x3" are its second number.
			return vector('f', type.length() == 6 ? type.charAt(5) - '0'
					: columns);
		}
		return null;
	}

	/**
	 * Return what "+", "-", "*", "/" or "%" between a and b gives, or
	 * null when GLSL does not allow it or a type is not known. A vector
	 * or a matrix with a number of its kind stays what it is; a matrix
	 * times a vector or a matrix follows its columns and rows, which
	 * differ for a matrix that is not square. Nothing is converted.
	 */
	static String arithmetic(String a, char operator, String b) {
		if (a == null || b == null) {
			return null;
		}
		int ca = columns(a);
		int cb = columns(b);
		if (ca > 0 && rows(a) == 0 || cb > 0 && rows(b) == 0) {
			return null;
		} else if (operator == '*' && (ca > 0 || cb > 0)) {
			if (ca > 0 && cb > 0) {
				// The columns of the left are the rows of the right.
				return ca == rows(b) ? matrix(cb, rows(a)) : null;
			} else if (ca > 0 && components(b) > 1) {
				return base(b) == 'f' && components(b) == ca
						? vector('f', rows(a)) : null;
			} else if (cb > 0 && components(a) > 1) {
				return base(a) == 'f' && components(a) == rows(b)
						? vector('f', cb) : null;
			}
		}
		if (ca > 0 || cb > 0) {
			// A matrix with itself, or with a float.
			return ca > 0 && cb > 0 ? ca == cb && rows(a) == rows(b) ? a
					: null : "float".equals(ca > 0 ? b : a) ? ca > 0 ? a : b
					: null;
		}
		int na = components(a);
		int nb = components(b);
		if (na == 0 || nb == 0 || base(a) != base(b) || base(a) == 'b') {
			return null;
		}
		return na == nb || nb == 1 ? a : na == 1 ? b : null;
	}

	/** Return the type of a number as it is written. */
	static String literal(CharSequence text) {
		int length = text.length();
		if (length == 0) {
			return null;
		}
		char last = text.charAt(length - 1);
		if (last == 'u' || last == 'U') {
			return "uint";
		}
		boolean hex = length > 1 && text.charAt(0) == '0' &&
				(text.charAt(1) == 'x' || text.charAt(1) == 'X');
		for (int i = 0; i < length && !hex; ++i) {
			char c = text.charAt(i);
			if (c == '.' || c == 'e' || c == 'E' || c == 'f' || c == 'F') {
				return "float";
			}
		}
		return "int";
	}

	/**
	 * Return what the built-in function name returns for arguments of
	 * these types, or null when it is no built-in function or the type
	 * it follows is not known.
	 */
	static String builtin(String name, List<String> arguments) {
		Character rule = BUILTINS.get(name);
		if (rule == null) {
			return null;
		}
		String first = arguments.isEmpty() ? null : arguments.get(0);
		switch (rule) {
			case 'a':
				return first;
			case 'l':
				return arguments.isEmpty() ? null
						: arguments.get(arguments.size() - 1);
			case 'f':
				return "float";
			case '3':
				return "vec3";
			case 't':
				// What an isampler or usampler holds are whole numbers,
				// a shadow sampler compares: without the sampler's type
				// nothing is known.
				return first == null || !first.contains("sampler") ? null
						: first.startsWith("isampler") ? "ivec4"
						: first.startsWith("usampler") ? "uvec4"
						: first.endsWith("Shadow") ? "float" : "vec4";
			case 'x':
				return columns(first) == 0 || rows(first) == 0 ? null
						: matrix(rows(first), columns(first));
			case 'o':
				String row = arguments.size() > 1 ? arguments.get(1) : null;
				return base(first) != 'f' || base(row) != 'f' ||
						components(first) < 2 || components(row) < 2 ? null
						: matrix(components(row), components(first));
			case 'B':
				return "bool";
			case 'b':
				return components(first) == 0 ? null
						: vector('b', components(first));
			case 'u':
				return "uint";
			case '2':
				return "vec2";
			default:
				return first == null ? null : first.contains("3D") ||
						first.contains("Array") ? "ivec3" : "ivec2";
		}
	}

	/**
	 * Tell whether arguments of these types can be the first arguments
	 * of a function with these parameter types. An argument whose type
	 * is not known fits any parameter. A parameter type of one capital
	 * letter, as the documentation writes a function for many types,
	 * stands for one type throughout; "T" is a number or vector of
	 * floats, "B" one of bools of T's size.
	 */
	static boolean accepts(List<String> parameters, List<String> arguments) {
		if (arguments.size() > parameters.size()) {
			return false;
		}
		String[] bound = new String[26];
		for (int i = 0; i < arguments.size(); ++i) {
			String argument = arguments.get(i);
			String parameter = parameters.get(i);
			if (argument == null || parameter == null) {
				continue;
			}
			char letter = parameter.length() == 1 ? parameter.charAt(0) : 0;
			if (letter < 'A' || letter > 'Z') {
				if (!parameter.equals(argument)) {
					return false;
				}
			} else if (letter == 'B') {
				String t = bound['T' - 'A'];
				if (base(argument) != 'b' || (t != null &&
						components(t) != components(argument))) {
					return false;
				}
			} else if (bound[letter - 'A'] == null) {
				bound[letter - 'A'] = argument;
			} else if (!bound[letter - 'A'].equals(argument)) {
				return false;
			}
		}
		return true;
	}
}
