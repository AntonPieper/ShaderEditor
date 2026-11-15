package de.markusfisch.android.shadereditor.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.opengl.PassFacts;
import de.markusfisch.android.shadereditor.project.HostDirective;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

public class PassFactLineTest {
	private static final ProjectPath FILE =
			ProjectPath.parse("ink.frag").value();
	private static final PassFactLine.Words WORDS = new PassFactLine.Words() {
		@Override
		public String error(int line) {
			return "error in line " + line;
		}

		@Override
		public String warning(int line) {
			return "warning in line " + line;
		}

		@Override
		public String everyPixel(String values) {
			return "every pixel " + values;
		}

		@Override
		public String values(String low, String high) {
			return "values " + low + " to " + high;
		}

		@Override
		public String nonFinite() {
			return "NaN or infinity";
		}

		@Override
		public String size(int width, int height) {
			return width + " × " + height;
		}

		@Override
		public String unchanged(long seconds) {
			return "unchanged for " + seconds + " s";
		}
	};

	// A pass that is black on purpose is told like any other.
	@Test
	public void oneValueEverywhereIsAFact() {
		var facts = facts(new float[]{0, 0, 0, 1}, null, null, false,
				1080, 2340);
		assertEquals("every pixel 0, 0, 0, 1",
				PassFactLine.second(facts, 1080, 2340, WORDS));
	}

	@Test
	public void nothingWorthSayingIsNoLine() {
		assertNull(PassFactLine.second(facts(null, new float[]{0, 0, 0, 0},
				new float[]{1, .5f, 1, 1}, false, 1080, 2340), 1080, 2340,
				WORDS));
	}

	@Test
	public void valuesOutsideZeroToOneGiveTheirRange() {
		var facts = facts(null, new float[]{1.8f, 2, 3, 1},
				new float[]{14.2f, 9, 3, 1}, false, 540, 1170);
		assertEquals("values 1.80 to 14.2",
				PassFactLine.second(facts, 1080, 2340, WORDS));
		var negative = facts(null, new float[]{-.6f, 0, 0, 1},
				new float[]{1, 1, 1, 1}, false, 1080, 2340);
		assertEquals("values −0.60 to 1",
				PassFactLine.second(negative, 1080, 2340, WORDS));
	}

	@Test
	public void theBuildsErrorComesFirstThenNaN() {
		var error = new ProjectDiagnostic(ProjectDiagnostic.Severity.ERROR,
				"x", FILE, 14);
		var warning = new ProjectDiagnostic(
				ProjectDiagnostic.Severity.WARNING, "y", FILE, 3);
		var facts = new PassFacts(FILE, true, List.of(warning, error), 1, 1,
				null, 96, null, null, null, true, 0);
		assertEquals("error in line 14",
				PassFactLine.second(facts, 1, 1, WORDS));
		var nan = facts(null, new float[]{0, 0, 0, 0},
				new float[]{1, 1, 1, 1}, true, 1, 1);
		assertEquals("NaN or infinity", PassFactLine.second(nan, 1, 1, WORDS));
	}

	@Test
	public void aSizeIsSaidOnlyWhenItIsNotTheScreens() {
		var facts = facts(null, null, null, false, 540, 1170);
		assertEquals("540 × 1170",
				PassFactLine.second(facts, 1080, 2340, WORDS));
		assertNull(PassFactLine.second(facts, 540, 1170, WORDS));
	}

	@Test
	public void theShownPassSaysItsFormatAndHowLongItStood() {
		var facts = new PassFacts(FILE, true, List.of(), 1, 1,
				HostDirective.Format.RGBA16F, 32, null, null, null, false,
				12_400_000_000L);
		assertEquals("rgba16f · unchanged for 12 s",
				PassFactLine.shown(facts, false, WORDS));
		// Paused, everything stands.
		assertEquals("rgba16f", PassFactLine.shown(facts, true, WORDS));
	}

	@Test
	public void theUsualFormatIsNotSaid() {
		var facts = new PassFacts(FILE, true, List.of(), 1, 1,
				HostDirective.Format.RGBA8, 96, null, null, null, false, 0);
		assertNull(PassFactLine.shown(facts, false, WORDS));
	}

	@Test
	public void numbersReadAsNumbers() {
		assertEquals("0", PassFactLine.number(0));
		assertEquals("1", PassFactLine.number(1));
		assertEquals("0.50", PassFactLine.number(.502f));
		assertEquals("−2.50", PassFactLine.number(-2.5f));
		assertEquals("140", PassFactLine.number(140.4f));
		assertEquals("0.00", PassFactLine.number(-.001f));
	}

	private static PassFacts facts(float[] same, float[] low, float[] high,
			boolean nonFinite, int width, int height) {
		return new PassFacts(FILE, true, List.of(), width, height,
				HostDirective.Format.RGBA8, 96, same, low, high, nonFinite, 0);
	}
}
