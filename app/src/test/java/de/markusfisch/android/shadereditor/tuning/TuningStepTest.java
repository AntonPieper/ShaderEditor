package de.markusfisch.android.shadereditor.tuning;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.opengl.Tuning;

public class TuningStepTest {
	@Test
	public void startsAtATenthOfTheSize() {
		assertEquals("0.01", first("0.3"));
		assertEquals("10", first("960.0"));
		assertEquals("1000", first("41231.7"));
		assertEquals("0.0001", first("1e-3"));
		assertEquals("1", first("45.0"));
	}

	@Test
	public void anIntStartsAtOneBelowTwenty() {
		assertEquals("1", first("3"));
		assertEquals("1", first("19"));
		assertEquals("10", first("256"));
		assertEquals("1", first("0"));
	}

	@Test
	public void aZeroStartsAtItsLastWrittenDigit() {
		assertEquals("0.1", first("0.0"));
		assertEquals("1", first("0."));
		assertEquals("0.01", first("0.00"));
	}

	@Test
	public void degreesStartAtFifteen() {
		assertEquals("15", TuningStep.label(TuningStep.first("30.0",
				new BigDecimal("30"), true)));
	}

	@Test
	public void theMenuOffersSixStepsAroundTheFirst() {
		assertEquals(List.of("0.001", "0.01", "0.05", "0.1", "0.5", "1"),
				labels(TuningStep.menu(new BigDecimal("0.01"), false, false)));
		assertEquals(List.of("1", "5", "10", "50", "100"),
				labels(TuningStep.menu(BigDecimal.ONE, true, false)));
		assertEquals(List.of("1", "10", "50", "100", "500", "1000"),
				labels(TuningStep.menu(BigDecimal.TEN, true, false)));
		assertEquals(List.of("1", "5", "15", "45", "90"),
				labels(TuningStep.menu(BigDecimal.valueOf(15), false, true)));
	}

	@Test
	public void eachStepAddsExactlyTheStep() {
		assertEquals(0, new BigDecimal("0.47").compareTo(TuningStep.move(
				new BigDecimal("0.37"), new BigDecimal("0.1"), 1,
				Tuning.Type.FLOAT)));
		assertEquals(0, new BigDecimal("-0.01").compareTo(TuningStep.move(
				new BigDecimal("0.01"), new BigDecimal("0.01"), -2,
				Tuning.Type.FLOAT)));
		// A uint stops at 0.
		assertEquals(0, BigDecimal.ZERO.compareTo(TuningStep.move(
				BigDecimal.ONE, BigDecimal.TEN, -1, Tuning.Type.UINT)));
	}

	@Test
	public void aTypedStepIsPositiveAndWholeForAnInt() {
		assertEquals("0.25", TuningStep.label(TuningStep.parse("0.25",
				Tuning.Type.FLOAT)));
		assertNull(TuningStep.parse("0.5", Tuning.Type.INT));
		assertNull(TuningStep.parse("-1", Tuning.Type.FLOAT));
		assertNull(TuningStep.parse("x", Tuning.Type.FLOAT));
	}

	private static String first(String literal) {
		return TuningStep.label(TuningStep.first(literal,
				LiteralText.value(literal), false));
	}

	private static List<String> labels(List<BigDecimal> steps) {
		List<String> labels = new ArrayList<>();
		for (BigDecimal step : steps) {
			labels.add(TuningStep.label(step));
		}
		return labels;
	}
}
