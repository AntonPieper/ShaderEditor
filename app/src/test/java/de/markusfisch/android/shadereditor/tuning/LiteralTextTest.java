package de.markusfisch.android.shadereditor.tuning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.math.BigDecimal;

public class LiteralTextTest {
	@Test
	public void readsTheExactValue() {
		assertEquals(0, new BigDecimal("0.3").compareTo(LiteralText.value("0.3")));
		assertEquals(0, BigDecimal.ONE.compareTo(LiteralText.value("1.")));
		assertEquals(0, new BigDecimal("0.5").compareTo(LiteralText.value(".5")));
		assertEquals(0, new BigDecimal("0.001").compareTo(LiteralText.value("1e-3")));
		assertEquals(0, new BigDecimal("2.5").compareTo(LiteralText.value("2.5f")));
		assertEquals(0, BigDecimal.valueOf(31).compareTo(LiteralText.value("0x1F")));
		assertEquals(0, BigDecimal.valueOf(15).compareTo(LiteralText.value("017")));
		assertEquals(0, BigDecimal.valueOf(7).compareTo(LiteralText.value("7u")));
		assertNull(LiteralText.value("x"));
	}

	@Test
	public void writesInDecimalNeverAsAFloatPrints() {
		assertEquals("0.34", write("0.3", "0.34", "0.01"));
		assertEquals("0.47", write("0.37", "0.47", "0.1"));
		// As many decimals as the step or the literal, whichever more.
		assertEquals("0.40", write("0.30", "0.4", "0.1"));
		assertEquals("970.0", write("960.0", "970", "10"));
	}

	@Test
	public void keepsTheLiteralsStyle() {
		assertEquals("2.", write("1.", "2", "1"));
		assertEquals("1.5", write("1.", "1.5", "0.1"));
		assertEquals(".6", write(".5", "0.6", "0.1"));
		assertEquals("-.6", write(".5", "-0.6", "0.1"));
		assertEquals("1.3e-3", write("1e-3", "0.0013", "0.0001"));
		assertEquals("2.5f", write("2.0f", "2.5", "0.5"));
		assertEquals("0x20", write("0x1F", "32", "1"));
		assertEquals("0X1A", write("0X1F", "26", "1"));
		assertEquals("020", write("017", "16", "1"));
		assertEquals("8u", write("7u", "8", "1"));
	}

	@Test
	public void aFloatKeepsItsPointAnIntGetsNone() {
		assertEquals("0.0", write("1.0", "0", "1"));
		assertEquals("4", write("3", "4", "1"));
		assertEquals("-2", write("3", "-2", "5"));
	}

	@Test
	public void noMinusInFrontOfZero() {
		assertEquals("0.00", write("0.01", "0.00", "0.01"));
	}

	@Test
	public void typedIsWrittenAsTyped() {
		assertEquals("0.350", LiteralText.typed("0.3", "0.350"));
		assertEquals("2.0", LiteralText.typed("0.3", "2"));
		assertEquals("-1.5", LiteralText.typed("0.3", "-1.5"));
		assertEquals("1e-4", LiteralText.typed("1e-3", "1e-4"));
		assertEquals("12", LiteralText.typed("3", "12"));
		assertEquals("12u", LiteralText.typed("3u", "12"));
		assertNull(LiteralText.typed("3", "1.5"));
		assertNull(LiteralText.typed("3u", "-1"));
		assertNull(LiteralText.typed("0.3", "abc"));
		assertNull(LiteralText.typed("0.3", "--1"));
	}

	private static String write(String literal, String value, String step) {
		return LiteralText.write(literal, new BigDecimal(value),
				new BigDecimal(step));
	}
}
