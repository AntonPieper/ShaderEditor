package de.markusfisch.android.shadereditor.tuning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import de.markusfisch.android.shadereditor.opengl.Tuning;

public class NumberLiteralTest {
	@Test
	public void floatsHaveAPointAnExponentOrAnF() {
		assertEquals(new NumberLiteral(Tuning.Type.FLOAT, 0.3), parse("0.3"));
		assertEquals(new NumberLiteral(Tuning.Type.FLOAT, 1.0), parse("1."));
		assertEquals(new NumberLiteral(Tuning.Type.FLOAT, 0.5), parse(".5"));
		assertEquals(new NumberLiteral(Tuning.Type.FLOAT, 1e-3), parse("1e-3"));
		assertEquals(new NumberLiteral(Tuning.Type.FLOAT, 2.5), parse("2.5f"));
	}

	@Test
	public void intsAreDecimalOctalOrHex() {
		assertEquals(new NumberLiteral(Tuning.Type.INT, 3), parse("3"));
		assertEquals(new NumberLiteral(Tuning.Type.INT, 0), parse("0"));
		assertEquals(new NumberLiteral(Tuning.Type.INT, 15), parse("017"));
		// An e in hex is a digit, not an exponent.
		assertEquals(new NumberLiteral(Tuning.Type.INT, 0x1e), parse("0x1E"));
		assertEquals(new NumberLiteral(Tuning.Type.UINT, 31), parse("0x1Fu"));
		assertEquals(new NumberLiteral(Tuning.Type.UINT, 7), parse("7u"));
		assertEquals(new NumberLiteral(Tuning.Type.UINT, 0xffffffffL),
				parse("0xffffffffu"));
		assertNull(parse("0x100000000"));
	}

	@Test
	public void itIsExactlyOneNumberOfTheCode() {
		String text = "float a = -0.25; // 0.5\nint b = 12;\n";
		int at = text.indexOf("0.25");
		assertEquals(new NumberLiteral(Tuning.Type.FLOAT, 0.25),
				NumberLiteral.at(text, at, at + 4));
		// The minus is an operator.
		assertNull(NumberLiteral.at(text, at - 1, at + 4));
		// Part of a number, or more than one.
		assertNull(NumberLiteral.at(text, at, at + 3));
		assertNull(NumberLiteral.at(text, text.indexOf("12"),
				text.indexOf("12") + 1));
		// In a comment.
		int comment = text.indexOf("0.5");
		assertNull(NumberLiteral.at(text, comment, comment + 3));
		// Not a number at all.
		assertNull(NumberLiteral.at(text, 0, 5));
		// An octal number with an 8 is no number.
		assertNull(NumberLiteral.at("int c = 018;", 8, 11));
	}

	private static NumberLiteral parse(String literal) {
		return NumberLiteral.at("x = " + literal + ";", 4,
				4 + literal.length());
	}
}
