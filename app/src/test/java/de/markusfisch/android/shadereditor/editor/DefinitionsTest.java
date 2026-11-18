package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.ProjectKnowledge;
import de.markusfisch.android.shadereditor.project.ProjectPath;
import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
import de.markusfisch.android.shadereditor.syntax.GlslSyntax;
import de.markusfisch.android.shadereditor.syntax.Knowledge;

public class DefinitionsTest {
	private static final String MAIN = """
			float sd(vec2 p, float r);
			float sd(vec2 p, float r) { return length(p) - r; }
			float sd(vec3 p, float r) { return length(p) - r; }
			float glow(float x);
			#define SAT(x) clamp(x, 0.0, 1.0)
			#if A
			float g = 1.0;
			#else
			float g = 2.0;
			#endif
			void main() {
				float d = sd(vec2(0.0), 1.0) + SAT(g) + glow(d) + lib + nope;
			}
			""";
	private static final String LIB = "float lib = 1.0;\nfloat glow(float x) { return x; }\n";
	// A second file around that declares lib too.
	private static final String LIB2 = "float lib = 2.0;\n";

	@Test
	public void foldsAPrototypeIntoItsBodyAndListsOverloads() {
		Definitions.Found found = find(MAIN, "sd(vec2(", "sd");
		assertEquals(Arrays.asList(1, 2), lines(MAIN, found));
		assertFalse(found.here());
	}

	@Test
	public void listsBothBranchesOfAnIf() {
		assertEquals(Arrays.asList(6, 8), lines(MAIN,
				find(MAIN, "SAT(g)", "g", 4)));
	}

	@Test
	public void aMacroParameterIsOnItsDefineLine() {
		assertEquals(List.of(4), lines(MAIN, find(MAIN, "x, 0.0", "x")));
	}

	@Test
	public void aLocalHidesWhatIsDeclaredOutside() {
		String text = "float r = 1.0;\nvoid main() {\n\tfloat r = 2.0;\n\tr;\n}\n";
		assertEquals(List.of(2), lines(text, find(text, "r;\n}", "r")));
	}

	@Test
	public void saysWhenTheCaretIsOnTheDeclaration() {
		assertTrue(find(MAIN, "sd(vec3", "sd").here());
	}

	@Test
	public void asksTheFilesAroundAndKeepsBoth() {
		Definitions.Found found = find(MAIN, "lib +", "lib");
		assertEquals(1, found.places().size());
		assertEquals("lib.glsl", found.places().get(0).file());
		// The prototype here and the body in the other file: which one
		// is the body is not told there, so both are listed.
		assertEquals(2, find(MAIN, "glow(d)", "glow").places().size());
	}

	@Test
	public void listsWhatTwoFilesAroundDeclareAlike() {
		Definitions.Found found = Definitions.of(GlslKnowledge.of(MAIN),
				TWO_FILES, Text.of(MAIN), MAIN.indexOf("lib +"), "lib");
		assertEquals(2, found.places().size());
		assertEquals("lib2.glsl", found.places().get(1).file());
	}

	@Test
	public void aContinuedMacroIsReadWhole() {
		String glued = MAIN + "#define CAT(a, b) \\\n\ta ## b\n";
		assertFalse(find(glued, "nope", "nope").none());
	}

	@Test
	public void isSureOfNothingOnlyWithoutGluedNames() {
		Definitions.Found none = find(MAIN, "nope", "nope");
		assertTrue(none.places().isEmpty());
		assertTrue(none.none());
		String glued = MAIN + "#define CAT(a, b) a##b\n";
		assertFalse(find(glued, "nope", "nope").none());
	}

	@Test
	public void aLocalBehindAnIfIsListedWithWhatItMayHide() {
		String both = """
				void main() {
				#ifdef A
					float k = 1.0;
				#else
					float k = 2.0;
				#endif
					gl_FragColor = vec4(k);
				}
				""";
		// The reader sees the last one; it is not the one.
		Definitions.Found found = find(both, "vec4(k", "k", 5);
		assertEquals(List.of(4), lines(both, found));
		assertNull(found.only());
		String one = """
				float k = 0.0;
				void main() {
				#ifdef A
					float k = 1.0;
				#endif
					gl_FragColor = vec4(k);
				}
				""";
		assertEquals(Arrays.asList(3, 0), lines(one,
				find(one, "vec4(k", "k", 5)));
	}

	@Test
	public void aStatementAMacroStartsMayDeclareTheName() {
		String text = """
				float x = 0.0;
				#define DECL float x = 1.0
				#define CALL(m) m
				#define LOCAL(n) float n = 1.0;
				void main() {
					CALL(DECL);
					LOCAL(k)
					gl_FragColor = vec4(x + k);
				}
				""";
		Definitions.Found x = find(text, "x + k", "x");
		assertEquals(1, x.places().size());
		assertNull(x.only());
		Definitions.Found k = find(text, "k);", "k");
		assertTrue(k.places().isEmpty());
		assertFalse(k.none());
		// A macro in an expression declares nothing.
		String used = "float x = 0.0;\n#define SQ(a) ((a) * (a))\n" +
				"void main() { float y = SQ(2.0); y = x; }\n";
		assertNotNull(find(used, "x; }", "x").only());
	}

	@Test
	public void aWordInTheBodyOfAMacroMeansWhatItMeansWhereItIsUsed() {
		String text = "float x = 0.0;\n#define INC(a) x += a\n" +
				"void f() { float x = 1.0; INC(1.0); }\n";
		Definitions.Found found = find(text, "x += a", "x");
		assertNull(found.only());
		// But for a parameter of the macro.
		assertNotNull(find(text, "a\n", "a").only());
	}

	@Test
	public void whatCouldNotBeReadIsNotSaidToBeNowhere() {
		StringBuilder long_ = new StringBuilder(
				"float helper(float x) { return x; }\n// ");
		for (int i = 0; i < 70000; ++i) {
			long_.append('x');
		}
		String text = long_.append("\nvoid main() { helper(1.0); }\n")
				.toString();
		Text t = Text.of(text);
		Definitions.Found found = Definitions.of(Analysis.of(
						GlslSyntax.INSTANCE, t, 1, null).knowledge(), SCOPE, t,
				text.indexOf("helper(1.0)"), "helper");
		assertTrue(found.places().isEmpty());
		assertFalse(found.none());
	}

	@Test
	public void theFitsLineNamesNoFunctionAMacroTakesOver() {
		String text = """
				float sat(float x) { return x; }
				#define sat(x) clamp(x, 0.0, 1.0)
				void main() { gl_FragColor = vec4(sat(0.5)); }
				""";
		Definitions.Found found = find(text, "sat(0.5)", "sat");
		assertEquals(2, found.places().size());
		assertEquals(-1, Definitions.fitting(found.places(),
				List.of("float")));
	}

	@Test
	public void isSureOfFilesAroundOnlyWhenTheBuildReadThemWhole() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "#include \"lib.glsl\"\n" +
				"void main() { gl_FragColor = vec4(near(1.0) + nope); }\n");
		files.put("lib.glsl", "float near(float x) { return x; }\n");
		ProjectKnowledge built = knowledge(files);
		assertNotNull(findIn(built, files, "main.frag", "near(", "near")
				.only());
		assertTrue(findIn(built, files, "main.frag", "nope", "nope")
				.none());
		// A file the build did not read.
		files.put("other.frag", "#include \"lib.glsl\"\n" +
				"void main() { gl_FragColor = vec4(nope); }\n");
		assertFalse(findIn(built, files, "other.frag", "nope", "nope")
				.none());
		// An include the build has not read yet.
		files.put("main.frag", "#include \"lib.glsl\"\n" +
				"#include \"new.glsl\"\nvoid main() { nope; }\n");
		assertFalse(findIn(built, files, "main.frag", "nope", "nope")
				.none());
	}

	@Test
	public void aLocalNeedsNoWholeFilesAroundButNoNameThatMayBeAMacro() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "#include \"lib.glsl\"\nvoid main() {}\n");
		files.put("lib.glsl", "float g = 1.0;\n");
		ProjectKnowledge built = knowledge(files);
		// other.frag was not read by the build.
		files.put("other.frag", "#include \"lib.glsl\"\n" +
				"float top = 1.0;\n" +
				"void main() { float k = g; k += top; }\n" +
				"void f() { float k = 1.0; DECLARE(k); k += 1.0; }\n");
		assertNotNull(findIn(built, files, "other.frag", "k += top", "k")
				.only());
		assertNull(findIn(built, files, "other.frag", "top;", "top")
				.only());
		assertFalse(findIn(built, files, "other.frag", "g;", "g").none());
		assertNull(findIn(built, files, "other.frag", "k += 1.0", "k")
				.only());
	}

	@Test
	public void isNotSureOfFilesAroundThatCouldNotBeReadOrGlueNames() {
		StringBuilder lib = new StringBuilder(
				"float far(float x) { return x; }\n// ");
		for (int i = 0; i < 70000; ++i) {
			lib.append('x');
		}
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "#include \"lib.glsl\"\n" +
				"void main() { gl_FragColor = vec4(far(1.0)); }\n");
		files.put("lib.glsl", lib.append('\n').toString());
		assertFalse(findIn(knowledge(files), files, "main.frag", "far(",
				"far").none());
		files.clear();
		files.put("main.frag", "#define MAKE(n) float n##_f() { return 1.0; }\n" +
				"MAKE(foo)\n#include \"lib.glsl\"\nvoid main() {}\n");
		files.put("lib.glsl", "float bar() { return foo_f(); }\n");
		assertFalse(findIn(knowledge(files), files, "lib.glsl", "foo_f(",
				"foo_f").none());
	}

	@Test
	public void listsAFormAnIncluderDeclaresBesideOneOfAnInclude() {
		Map<String, String> files = new LinkedHashMap<>();
		files.put("main.frag", "float hash(vec2 p) { return p.x; }\n" +
				"#include \"lib.glsl\"\nvoid main() {}\n");
		files.put("lib.glsl", "#include \"common.glsl\"\n" +
				"float noise(vec2 p) { return hash(p); }\n");
		files.put("common.glsl", "float hash(float x) { return x; }\n");
		assertEquals(2, findIn(knowledge(files), files, "lib.glsl",
				"hash(p)", "hash").places().size());
	}

	@Test
	public void namesTheOneFormACallFits() {
		Definitions.Found found = find(MAIN, "sd(vec2(", "sd");
		assertEquals(0, Definitions.fitting(found.places(),
				Arrays.asList("vec2", "float")));
		assertEquals(1, Definitions.fitting(found.places(),
				Arrays.asList("vec3", "float")));
		assertEquals(-1, Definitions.fitting(found.places(),
				Arrays.asList(null, "float")));
	}

	@Test
	public void aFieldOrALayoutQualifierIsNoNameToLookUp() {
		String text = """
				int location = 0;
				layout(location = 0) out vec4 color;
				float r = 1.0;
				struct Disc { vec2 c; float r; };
				void main() {
					Disc d = Disc(vec2(0.0), r);
					float x = d.
						r + d \\
						.c.x;
				}
				""";
		Knowledge known = GlslKnowledge.of(text);
		Text t = Text.of(text);
		assertTrue(Definitions.noName(known, t, text.indexOf("r; }")));
		assertTrue(Definitions.noName(known, t, text.indexOf("r + d")));
		assertTrue(Definitions.noName(known, t, text.indexOf("c.x")));
		assertFalse(Definitions.noName(known, t, text.indexOf("r);")));
		assertFalse(Definitions.noName(known, t, text.indexOf("Disc d")));
		assertTrue(Definitions.noName(known, t, text.indexOf("location = 0)")));
		assertFalse(Definitions.noName(known, t, text.indexOf("location")));
	}

	private static Definitions.Found find(String text, String before,
			String word) {
		return find(text, before, word, 0);
	}

	private static Definitions.Found find(String text, String before,
			String word, int skip) {
		return Definitions.of(GlslKnowledge.of(text), SCOPE, Text.of(text),
				text.indexOf(before) + skip, word);
	}

	// Asks from file of files, with what built knows around, as
	// OpenProject.scope does.
	private static Definitions.Found findIn(ProjectKnowledge built,
			Map<String, String> files, String file, String before,
			String word) {
		String text = files.get(file);
		ProjectPath path = ProjectPath.parse(file).value();
		Knowledge.Scope scope = new Knowledge.Scope() {
			@Override
			public List<String> names() {
				return built.names(path);
			}

			@Override
			public List<Knowledge.Symbol> find(CharSequence word) {
				return built.find(path, word);
			}

			@Override
			public List<Knowledge.Symbol> all(CharSequence word) {
				return built.all(path, word);
			}

			@Override
			public int line(Knowledge.Symbol symbol) {
				return built.line(symbol);
			}

			@Override
			public List<Knowledge.Symbol> macros() {
				return built.macros(path);
			}

			@Override
			public boolean whole(Knowledge known) {
				return built.whole(path, known);
			}
		};
		return Definitions.of(GlslKnowledge.of(text), scope, Text.of(text),
				text.indexOf(before), word);
	}

	private static ProjectKnowledge knowledge(Map<String, String> files) {
		Map<ProjectPath, byte[]> bytes = new LinkedHashMap<>();
		for (Map.Entry<String, String> file : files.entrySet()) {
			bytes.put(ProjectPath.parse(file.getKey()).value(),
					file.getValue().getBytes(StandardCharsets.UTF_8));
		}
		return ProjectKnowledge.of(bytes, ProjectKnowledge.NONE);
	}

	private static List<Integer> lines(String text,
			Definitions.Found found) {
		List<Integer> lines = new ArrayList<>();
		for (Knowledge.Symbol place : found.places()) {
			lines.add(Text.of(text).lineOf(place.start()));
		}
		return lines;
	}

	private static final Knowledge LIB_KNOWN = GlslKnowledge.of(LIB);
	private static final Knowledge.Scope SCOPE = new Knowledge.Scope() {
		@Override
		public List<String> names() {
			return List.of("glow", "lib");
		}

		@Override
		public List<Knowledge.Symbol> find(CharSequence word) {
			List<Knowledge.Symbol> found = new ArrayList<>();
			for (Knowledge.Symbol symbol : LIB_KNOWN.at(-1, word)) {
				found.add(symbol.in("lib.glsl"));
			}
			return found;
		}

		@Override
		public int line(Knowledge.Symbol symbol) {
			return Text.of(LIB).lineOf(symbol.start());
		}

		@Override
		public boolean whole(Knowledge known) {
			return true;
		}
	};

	// As SCOPE, with a second file whose lib find does not tell.
	private static final Knowledge.Scope TWO_FILES = new Knowledge.Scope() {
		@Override
		public List<String> names() {
			return SCOPE.names();
		}

		@Override
		public List<Knowledge.Symbol> find(CharSequence word) {
			return SCOPE.find(word);
		}

		@Override
		public List<Knowledge.Symbol> all(CharSequence word) {
			List<Knowledge.Symbol> found = new ArrayList<>(SCOPE.find(word));
			for (Knowledge.Symbol symbol : GlslKnowledge.of(LIB2).at(-1,
					word)) {
				found.add(symbol.in("lib2.glsl"));
			}
			return found;
		}

		@Override
		public int line(Knowledge.Symbol symbol) {
			return 0;
		}
	};
}
