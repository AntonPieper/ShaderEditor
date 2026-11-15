package de.markusfisch.android.shadereditor.spike;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Map;

import de.markusfisch.android.shadereditor.editor.Text;

/**
 * SPIKE, throwaway: the claims of the code-knowledge study that a test
 * can hold. The measurements are the main methods of Baseline, Report,
 * Quality and Demo.
 */
public class SpikeTest {
	@Test
	public void bundledShadersParseWithoutProblems() {
		for (Map.Entry<String, String> e : Corpus.bundled().entrySet()) {
			GlslKnowledge k = GlslKnowledge.of(Text.of(e.getValue()),
					Knowledge.Scope.EMPTY);
			assertEquals(e.getKey(), 0, k.problems);
			assertTrue(e.getKey(), k.sema.typed * 100 >=
					k.sema.expressions * 90);
		}
	}

	@Test
	public void generatedCodeIsFullyTyped() {
		GlslKnowledge k = GlslKnowledge.of(Text.of(Corpus.generated(2000,
				42)), Knowledge.Scope.EMPTY);
		assertEquals(0, k.problems);
		assertEquals(k.sema.expressions, k.sema.typed);
		assertEquals(0, k.sema.unresolved);
	}

	@Test
	public void brokenCodeKeepsItsFunctions() {
		String source = Corpus.generated(1000, 42);
		int functions = GlslKnowledge.of(Text.of(source),
				Knowledge.Scope.EMPTY).symbols().size();
		for (Corpus.Break kind : Corpus.Break.values()) {
			String text = Corpus.broken(source, 500, kind);
			assertNotNull(text);
			int found = GlslKnowledge.of(Text.of(text),
					Knowledge.Scope.EMPTY).symbols().size();
			assertTrue(kind + ": " + found + " of " + functions,
					found >= functions - 3);
		}
	}

	@Test
	public void membersOfALineTypedLater() {
		String source = "struct Ray { vec3 origin; vec3 dir; };\n" +
				"void main() {\n\tRay r;\n\tvec2 uv;\n\n}\n";
		GlslKnowledge k = GlslKnowledge.of(Text.of(source),
				Knowledge.Scope.EMPTY);
		int at = source.indexOf("\n}\n");
		assertEquals("vec3", k.typeBefore("\tr.dir.", 7, at).toString());
		assertEquals("vec3", k.typeBefore("\tnormalize(r.dir + 1.0).", 24,
				at).toString());
		assertEquals("[origin, dir]", k.membersAt("\tr.", 3, at).toString());
	}
}
