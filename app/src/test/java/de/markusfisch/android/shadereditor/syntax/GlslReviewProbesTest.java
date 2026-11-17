package de.markusfisch.android.shadereditor.syntax;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/**
 * The cases of the first independent review of half 2 that are not
 * about uses: the types of matrices that are not square, and limits
 * that have to make a knowledge say that it is not complete. Those
 * about uses are UsesProbesTest; the three about cost are in
 * GlslLocalsTest and GlslCompletionTest.
 */
public class GlslReviewProbesTest {
	private static String type(String text, String declarations) {
		GlslStatement s = GlslStatementTest.statement(text);
		return GlslExpression.type(s, 0, s.size(), GlslKnowledge.of(
				declarations).names(0, Knowledge.Scope.NONE));
	}

	@Test
	public void rectangularTypes() {
		assertEquals("vec3", type("m*v", "mat2x3 m; vec2 v;"));
	}

	@Test
	public void rectangularTranspose() {
		assertEquals("mat3x2", type("transpose(m)", "mat2x3 m;"));
	}

	@Test
	public void truncatedStatementCannotBeComplete() {
		String s = "float x; void f(){float a=" + "1.+".repeat(1100) +
				"1.,x=0.; x++;}";
		assertFalse(GlslKnowledge.of(s).complete());
	}

	@Test
	public void droppedParameterBudgetCannotBeComplete() {
		String s = "float x; float f(float x){return x;}";
		GlslDeclarations b = new GlslDeclarations(100, 40, 100);
		LineTokens t = new LineTokens();
		GlslLexer.lexLine(s, false, GlslLexer.TEXT_START, t);
		b.addLine(s, 0, t);
		GlslKnowledge k = b.build();
		assertFalse(k.complete());
		// What the budget took of the symbol is still a local: the
		// name in the body is the parameter.
		assertEquals(Knowledge.Kind.PARAMETER, k.at(s.indexOf("x;}"), "x")
				.get(0).kind());
	}

	@Test
	public void symbolAndLocalCapsReported() {
		String s = "float x; void f(){int y; int z;}";
		GlslDeclarations b = new GlslDeclarations(1, 10000, 1);
		LineTokens t = new LineTokens();
		GlslLexer.lexLine(s, false, GlslLexer.TEXT_START, t);
		b.addLine(s, 0, t);
		assertFalse(b.build().complete());
	}

	@Test
	public void parameterPartsCapCannotBeComplete() {
		StringBuilder s = new StringBuilder("float x; void f(");
		for (int i = 0; i < 256; i++) {
			s.append("float p").append(i).append(",");
		}
		s.append("float x){x++;}");
		assertFalse(GlslKnowledge.of(s.toString()).complete());
	}
}
