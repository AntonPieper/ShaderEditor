package de.markusfisch.android.shadereditor.adapter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OutlineSpanTest {
	@Test
	public void strokeIsFourPercentOfTheTextSize() {
		assertEquals(1.6f, OutlineSpan.strokeWidth(40f), 1e-6f);
	}
}
