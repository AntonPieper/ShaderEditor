package exact;

/** The reviews' inputs, each with what a compiler makes of it. */
final class Cases {
	static void c(String id, String text, String word, int k,
			String expected) {
		String actual;
		try {
			actual = Main.status(text, word, k);
		} catch (Throwable t) {
			actual = "THROWS " + t;
		}
		Main.expect(id, expected, actual);
	}

	static void r(String id, String text, String word, int k, String name,
			boolean allowed) {
		String why;
		try {
			why = Main.rename(text, word, k, name);
		} catch (Throwable t) {
			why = "THROWS " + t;
		}
		Main.expect(id, allowed ? "null" : "refused",
				why == null ? "null" : why.startsWith("THROWS") ? why
						: allowed ? why : "refused");
	}

	static void run() {
		String v3 = "#version 300 es\nprecision highp float;\n";
		// Review 1.
		c("R1 F1", "int x; void f(){for(int x=0;x<2;x++) if(true) x++; " +
				"else x--; x++;}", "x", 0, "T O O O O O T");
		c("R1 F2", "bool x; void f(){while(bool x=true){x=false;} x=false;}",
				"x", 0, "T O O T");
		c("R1 F3", "int x; void f(){\n#ifdef MISSING\nint x;\n#else\nx++;\n" +
				"#endif\n}", "x", 0, "T - T");
		c("R1 F4a", "float x;\n#define x 1.0\nvoid f(){float y=x;}", "x", 0,
				"T O O");
		c("R1 F4b", "#define x(a) (a)\nfloat x; void f(){x=1.;}", "x", 1,
				"O T T");
		c("R1 F5a", "#define X 1\n#if 0\n#undef X\n#endif\nint a=X;", "X", 0,
				"T - T");
		c("R1 F5b", "#define ON\n#undef ON\nfloat x;\n#ifdef ON\nfloat a=x;\n" +
				"#else\nfloat b=x;\n#endif", "x", 0, "T - T");
		c("R1 F6a", "float x;\n#if 0 // disabled\nfloat a=x;\n#endif", "x", 0,
				"T -");
		c("R1 F6b", v3 + "float x;\n#if \\\n0\nfloat a=x;\n#endif", "x", 0,
				"T -");
		c("R1 F6c", "float x;\n#if 0\n#elif 0\nfloat a=x;\n#endif", "x", 0,
				"T -");
		c("R1 F7", "#define X 1\n#pragma X\nvoid f(){int a=X;}", "X", 0,
				"T - T");
		c("R1 F8", "float x;\n#define CAT(a) x ## a", "x", 0, "T -");
		c("R1 F9", v3 + "float f(vec2 p); float f(vec3 p); void g(){mat2x3 " +
				"m; vec2 v; f(m*v);}", "f", 0, "T T T");
		c("R1 F10", v3 + "#define TEX tex\nuniform highp sampler2DShadow " +
				"tex;\nfloat f(float p); float f(vec4 p);\nvoid g(){f(" +
				"texture(TEX,vec3(0.)));}", "f", 0, "T T T");
		c("R1 F11", "float x; void f(){float a=" + "1.+".repeat(1100) +
				"1.,x=0.; x++;}", "x", 0, "T O O");
		StringBuilder params = new StringBuilder();
		for (int i = 0; i < 256; ++i) {
			params.append("float p").append(i).append(',');
		}
		c("R1 F14", "float x; void f(" + params + "float x){x++;}", "x", 0,
				"T O O");
		// Review 2.
		c("R2 P1-1", "float x;\n#define CALL(m) m(1.)\n#define DECL(v) " +
				"float x = v;\nvoid f(){ CALL(DECL) x++; }", "x", 0, "T O O");
		c("R2 P1-2", "float x;\n#define ID(a) a\nvoid f(){ ID(float) x = 1.;" +
				" x++; }", "x", 0, "T O O");
		c("R2 P1-3", "float f(vec2 p){return p.x;}\nfloat f(vec3 p){return " +
				"p.y;}\n#define vec3 vec2\nvoid g(){ vec3 v = vec3(1.); " +
				"f(v); }", "f", 0, "T T T");
		c("R2 P1-4", v3 + "float X;\n#define X\\\n(a) a\nvoid f(){ X = 1.; }",
				"X", 0, "T O T");
		c("R2 P1-5a", "float x;\nvoid f(){ float x = 1.; x++; }", "x", 0,
				"T O O");
		c("R2 P1-5b", "float x;\nvoid f(){ float xé = 1.; x++; }", "x",
				0, "T O O");
		c("R2 P2-2a", "float f(float p); float f(vec2 p);\n#define VEC vec2\n" +
				"void g(){VEC v; f(v);}", "f", 0, "T T T");
		c("R2 P2-2b", "struct S{float x;};\n#define SS S\nvoid g(){SS s; " +
				"s.x=1.;}", "x", 0, "T T");
		c("R2 P3", "float x;\n#define X 1. /* a\nb */ + x\nfloat y = X;", "x",
				0, "FAIL*");
		c("R2 Q3", "#define y 2.\n#undef y\nfloat y; void f(){ y++; }", "y",
				2, "O O T T");
		// The model review.
		c("M a", "float x;\n#define DECL float x = 1.;\n#define N 3.\n" +
				"#ifdef GL_EXT_shader_texture_lod\n#undef N\n#define N DECL\n" +
				"#endif\nvoid f(){ N; x++; }", "x", 0, "T T T");
		c("M b", "float x; void f(){\n#ifdef GL_ES\n  float x = 1.;\n#endif\n" +
				"  x++; }", "x", 0, "T O O");
		c("M g1", "float f(){return 1.;} void g(){ float f = f(); f += 1.; }",
				"f", 0, "T O T O");
		c("M g2", v3 + "int location; layout(location = 0) out vec4 c;",
				"location", 0, "T O");
		c("M g3", v3 + "uniform B { float x; }; void f(){ float y = x; " +
				"float x = 1.; y = x; }", "x", 0, "T T O O");
		c("M Q5", "float x;\n#define INC x++\nvoid f(){ float x; INC; }\n" +
				"void g(){ INC; }", "x", 0, "T T T");
		// Review 3.
		c("R3 F1", "#define RET return\nfloat x; float f(){ RET x; }", "x", 0,
				"T T");
		c("R3 F3a", "float f(float a);\nvoid g(){f(1.);}\nfloat f(float a)" +
				"{return a;}", "f", 2, "T T T");
		c("R3 F3b", v3 + "#define N 3\nfloat f(float a[N]);\nfloat f(float " +
				"a[N]){return a[0];}\nvoid main(){float a[N];f(a);}", "f", 0,
				"T T T");
		c("R3 F4", v3 + "uniform B{float x;}b;\nvoid main(){float a=b.x;}",
				"x", 0, "T T");
		c("R3 F6", "float X;\n#define X(a) a\nvoid f(){X++;}", "X", 0,
				"T O T");
		c("R3 F7", "#if " + "!".repeat(12000) + "0\nfloat x;\n#endif\n" +
				"void main(){}", "x", 0, "-");
		// Review 4.
		c("R4 F2", v3 + "int f(float a[02]);\nint f(float a[2]){return 1;}\n" +
				"void main(){float a[2];f(a);}", "f", 0, "T T T");
		c("R4 F3", "#define N 44\n#line N\nvoid main(){}", "N", 0, "T T");
		c("R4 F4a", "int length;\nstruct S{int length;};\nvoid main(){S s;" +
				"s.length=1;}", "length", 0, "T O O");
		c("R4 F4b", v3 + "struct S{int length;};\nvoid main(){S s; float " +
				"a[2]; s.length=a.length();}", "length", 0, "T T O");
		c("R4 F9a", "struct A{int x;}; struct B{int y;};\nint f(A a){return " +
				"0;} int f(B b){return 1;}\nvoid main(){f(B(1));}", "B", 0,
				"T T T");
		c("R4 F14", "float " + "a".repeat(301) + "; void main(){ " +
				"a".repeat(301) + " = 1.; }", "a".repeat(301), 0, "T T");
		c("R4 F15", "#define K 1\n#if 1\n#elif K\n#endif\nvoid main(){}", "K",
				0, "T -");
		// The check behind a rename.
		r("R4 F6", "#define OLD 1\n#ifdef NEW\nconst int a=2;\n#else\nconst " +
				"int a=1;\n#endif\nvoid main(){int b=a;}", "OLD", 0, "NEW",
				false);
		r("capture", "float f(){ float a = 1.; { float b = 2.; return a + b; " +
				"} }", "a", 0, "b", false);
		r("hides", "float b; float f(){ float a = 1.; return a + b; }", "a",
				0, "b", false);
		r("free", "float b; float f(){ float a = 1.; return a; }\nfloat g(){" +
				" float b = 2.; return b; }", "a", 0, "c", true);
		r("macro taken", "#define A 1\n#define B 2\nint x = A + B;", "A", 0,
				"B", false);
		r("M Q5 rename", "float x;\n#define INC x++\nvoid f(){ float x; INC;" +
				" }\nvoid g(){ INC; }", "x", 2, "y", true);
		// Adverse sizes: the time is printed, a crash is a failure.
		time("R4 F1 8000 macros, 70000 lines", () -> {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < 8000; ++i) {
				sb.append("#define M").append(i).append("(x) x\n");
			}
			sb.append("void main(){int aaaaa;\n");
			sb.append("aaaaa++;\n".repeat(70000)).append("}");
			c("R4 F1", sb.toString(), "aaaaa", 0, "T*");
		});
		time("R4 F12 100000 #elif", () -> c("R4 F12", "#if 0\n" +
				"#elif 0\n".repeat(100000) + "#endif\nint x;void main(){}",
				"x", 0, "T"));
		time("R4 F13 112000 places and an emoji", () -> c("R4 F13",
				("int x;void main(){" + "x++;".repeat(14000) + "}\n")
						.repeat(1).replace("}\n", "") + ("\n" +
						"x++;".repeat(14000)).repeat(7) + "} // 😀",
				"x", 0, "T*"));
		time("deep parentheses", () -> c("deep", "float x = " +
				"(".repeat(5000) + "1." + ")".repeat(5000) + ";", "x", 0,
				"FAIL*"));
		System.out.println(Main.passed + " as expected, " + Main.failed +
				" wrong");
	}

	static void time(String what, Runnable r) {
		long start = System.nanoTime();
		r.run();
		System.out.println(what + ": " + (System.nanoTime() - start) /
				1_000_000 + " ms");
	}
}
