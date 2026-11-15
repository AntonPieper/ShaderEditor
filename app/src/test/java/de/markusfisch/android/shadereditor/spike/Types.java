package de.markusfisch.android.shadereditor.spike;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SPIKE, throwaway: the types of GLSL ES 1.00 and 3.00 as values, and
 * the rules for members and swizzles.
 */
final class Types {
	static final int VOID = 0;
	static final int SCALAR = 1;
	static final int VECTOR = 2;
	static final int MATRIX = 3;
	static final int SAMPLER = 4;
	static final int STRUCT = 5;
	static final int ARRAY = 6;
	static final int UNKNOWN = 7;

	static final int FLOAT = 0;
	static final int INT = 1;
	static final int UINT = 2;
	static final int BOOL = 3;

	/** One type. Built-in types are one object each, compare with ==. */
	static final class Type {
		final int kind;
		final int base;
		// Components of a vector, columns of a matrix, length of an
		// array (-1 unsized).
		final int size;
		final int rows;
		final String name;
		final Type element;
		// For a struct: its fields in order.
		int[] fieldNames;
		Type[] fieldTypes;
		Object declaration;

		Type(int kind, int base, int size, int rows, String name,
				Type element) {
			this.kind = kind;
			this.base = base;
			this.size = size;
			this.rows = rows;
			this.name = name;
			this.element = element;
		}

		boolean isNumeric() {
			return kind == SCALAR || kind == VECTOR || kind == MATRIX;
		}

		@Override
		public String toString() {
			return kind == ARRAY
					? element + "[" + (size < 0 ? "" : size) + "]"
					: name;
		}
	}

	static final Type T_UNKNOWN = new Type(UNKNOWN, 0, 0, 0, "?", null);
	static final Type T_VOID = new Type(VOID, 0, 0, 0, "void", null);
	static final Type T_FLOAT = scalar(FLOAT, "float");
	static final Type T_INT = scalar(INT, "int");
	static final Type T_UINT = scalar(UINT, "uint");
	static final Type T_BOOL = scalar(BOOL, "bool");
	// VECTORS[base][components - 2]
	static final Type[][] VECTORS = new Type[4][3];
	// MATRICES[columns - 2][rows - 2]
	static final Type[][] MATRICES = new Type[3][3];

	private static final Map<String, Type> BY_NAME = new HashMap<>();
	// By the number Names gives the type's name.
	private static Type[] byId = new Type[256];
	private static final String[] PREFIX = {"", "i", "u", "b"};

	static {
		register(T_VOID);
		register(T_FLOAT);
		register(T_INT);
		register(T_UINT);
		register(T_BOOL);
		for (int base = 0; base < 4; ++base) {
			for (int n = 2; n <= 4; ++n) {
				VECTORS[base][n - 2] = register(new Type(VECTOR, base, n, 0,
						PREFIX[base] + "vec" + n, null));
			}
		}
		for (int c = 2; c <= 4; ++c) {
			for (int r = 2; r <= 4; ++r) {
				Type t = new Type(MATRIX, FLOAT, c, r,
						c == r ? "mat" + c : "mat" + c + "x" + r, null);
				MATRICES[c - 2][r - 2] = register(t);
				if (c == r) {
					alias("mat" + c + "x" + r, t);
				}
			}
		}
		for (String s : new String[]{"sampler2D", "sampler3D", "samplerCube",
				"sampler2DShadow", "samplerCubeShadow", "sampler2DArray",
				"sampler2DArrayShadow", "isampler2D", "isampler3D",
				"isamplerCube", "isampler2DArray", "usampler2D", "usampler3D",
				"usamplerCube", "usampler2DArray", "samplerExternalOES"}) {
			register(new Type(SAMPLER, s.charAt(0) == 'i' ? INT
					: s.charAt(0) == 'u' ? UINT : FLOAT, 0, 0, s, null));
		}
	}

	private Types() {
	}

	static void touch() {
	}

	private static Type scalar(int base, String name) {
		return new Type(SCALAR, base, 1, 0, name, null);
	}

	private static Type register(Type t) {
		alias(t.name, t);
		return t;
	}

	private static void alias(String name, Type t) {
		BY_NAME.put(name, t);
		int id = Names.seed(name);
		if (id >= byId.length) {
			byId = java.util.Arrays.copyOf(byId, id * 2);
		}
		byId[id] = t;
	}

	/** Return the built-in type named so, or null. */
	static Type named(String name) {
		return BY_NAME.get(name);
	}

	/** Return the built-in type whose name has this number, or null. */
	static Type byNameId(int id) {
		return id >= 0 && id < byId.length ? byId[id] : null;
	}

	static Type vector(int base, int components) {
		return components == 1
				? base == FLOAT ? T_FLOAT : base == INT ? T_INT
				: base == UINT ? T_UINT : T_BOOL
				: VECTORS[base][components - 2];
	}

	static Type array(Type element, int size) {
		return new Type(ARRAY, 0, size, 0, null, element);
	}

	static Type struct(String name) {
		return new Type(STRUCT, 0, 0, 0, name, null);
	}

	/**
	 * Return the type of the field or swizzle of a value of type, or
	 * unknown. "length" of an array is a method and answers int.
	 */
	static Type member(Type type, CharSequence text, int start, int end,
			int nameId) {
		if (type.kind == STRUCT) {
			if (type.fieldNames != null) {
				for (int i = 0; i < type.fieldNames.length; ++i) {
					if (type.fieldNames[i] == nameId) {
						return type.fieldTypes[i];
					}
				}
			}
			return T_UNKNOWN;
		} else if (type.kind == VECTOR) {
			int n = end - start;
			if (n < 1 || n > 4) {
				return T_UNKNOWN;
			}
			int set = -1;
			for (int i = start; i < end; ++i) {
				char c = text.charAt(i);
				int s = "xyzw".indexOf(c) >= 0 ? 0
						: "rgba".indexOf(c) >= 0 ? 1
						: "stpq".indexOf(c) >= 0 ? 2 : -1;
				int index = s < 0 ? -1
						: (s == 0 ? "xyzw" : s == 1 ? "rgba" : "stpq")
								.indexOf(c);
				if (s < 0 || (set >= 0 && s != set) || index >= type.size) {
					return T_UNKNOWN;
				}
				set = s;
			}
			return vector(type.base, n);
		} else if (type.kind == ARRAY && nameId == Names.LENGTH) {
			return T_INT;
		}
		return T_UNKNOWN;
	}

	/**
	 * Return what may follow a "." after a value of type: the fields of
	 * a struct, the components of a vector, "length" of an array.
	 */
	static List<String> members(Type type, Names names) {
		List<String> out = new ArrayList<>();
		if (type.kind == STRUCT && type.fieldNames != null) {
			for (int id : type.fieldNames) {
				out.add(names.name(id));
			}
		} else if (type.kind == VECTOR) {
			for (String set : new String[]{"xyzw", "rgba", "stpq"}) {
				for (int i = 0; i < type.size; ++i) {
					out.add(String.valueOf(set.charAt(i)));
				}
			}
			out.add("xy");
			if (type.size > 2) {
				out.add("xyz");
				out.add("rgb");
			}
		} else if (type.kind == ARRAY) {
			out.add("length");
		}
		return out;
	}
}
