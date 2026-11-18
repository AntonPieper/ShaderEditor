package de.markusfisch.android.shadereditor.editor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.markusfisch.android.shadereditor.syntax.GlslKnowledge;
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
		assertFalse(find(glued, "nope", "nope").sure());
	}

	@Test
	public void isSureOfNothingOnlyWithoutGluedNames() {
		Definitions.Found none = find(MAIN, "nope", "nope");
		assertTrue(none.places().isEmpty());
		assertTrue(none.sure());
		String glued = MAIN + "#define CAT(a, b) a##b\n";
		assertFalse(find(glued, "nope", "nope").sure());
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

	private static Definitions.Found find(String text, String before,
			String word) {
		return find(text, before, word, 0);
	}

	private static Definitions.Found find(String text, String before,
			String word, int skip) {
		return Definitions.of(GlslKnowledge.of(text), SCOPE, Text.of(text),
				text.indexOf(before) + skip, word);
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
