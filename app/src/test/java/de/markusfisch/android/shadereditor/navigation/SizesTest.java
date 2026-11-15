package de.markusfisch.android.shadereditor.navigation;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Locale;

/** One way to say a size, the same on every Android version. */
public class SizesTest {
	@Test
	public void unitsOfAThousand() {
		said("512", Sizes.Unit.BYTES, 512);
		said("1.5", Sizes.Unit.KILOBYTES, 1500);
		said("88", Sizes.Unit.KILOBYTES, 88_000);
		said("101", Sizes.Unit.KILOBYTES, 101_376);
		said("1.0", Sizes.Unit.MEGABYTES, 999_950);
		// The largest file the editor opens, 16 MiB.
		said("17", Sizes.Unit.MEGABYTES, 16L * 1024 * 1024);
		said("2.5", Sizes.Unit.GIGABYTES, 2_500_000_000L);
	}

	@Test
	public void theDecimalIsTheLanguages() {
		assertEquals("1,5", Sizes.of(1500, Locale.GERMANY).number());
	}

	private static void said(String number, Sizes.Unit unit, long bytes) {
		Sizes.Said said = Sizes.of(bytes, Locale.US);
		assertEquals(number, said.number());
		assertEquals(unit, said.unit());
	}
}
