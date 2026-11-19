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

	// F2: GLSL ES 3.00 (4.1.3) keeps the bit pattern of an int literal,
	// so one with the high bit set is negative.
	@Test
	public void anIntIsItsSigned32BitPattern() {
		assertEquals(new NumberLiteral(Tuning.Type.INT, -1),
				parse("0xffffffff"));
		assertEquals(new NumberLiteral(Tuning.Type.INT, -1),
				parse("4294967295"));
		assertEquals(new NumberLiteral(Tuning.Type.INT, Integer.MIN_VALUE),
				parse("020000000000"));
		assertEquals(new NumberLiteral(Tuning.Type.INT, Integer.MAX_VALUE),
				parse("2147483647"));
		// A uint keeps all 32 bits as its value.
		assertEquals(new NumberLiteral(Tuning.Type.UINT, 0xffffffffL),
				parse("0xffffffffu"));
	}

	@Test
	public void anOctalNumberWithAnEightIsNone() {
		assertNull(parse("018"));
	}

	private static NumberLiteral parse(String literal) {
		return NumberLiteral.parse(literal);
	}
}
