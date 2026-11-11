package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class ShaderLineMappingTest {
	@Test
	public void identityKeepsLines() {
		ShaderLineMapping mapping = ShaderLineMapping.identity();
		assertEquals(1, mapping.toSourceLine(1));
		assertEquals(42, mapping.toSourceLine(42));
	}

	@Test
	public void invalidPreparedLinesMapToMinusOne() {
		ShaderLineMapping mapping = ShaderLineMapping.identity();
		assertEquals(-1, mapping.toSourceLine(0));
		assertEquals(-1, mapping.toSourceLine(-3));
	}

	@Test
	public void leadingInsertedLinesAreShiftedOut() {
		ShaderLineMapping mapping = ShaderLineMapping.withLeadingInsertedLines(2);
		assertEquals(-1, mapping.toSourceLine(1));
		assertEquals(-1, mapping.toSourceLine(2));
		assertEquals(1, mapping.toSourceLine(3));
		assertEquals(8, mapping.toSourceLine(10));
	}

	@Test
	public void insertedLinesAfterSourceLineMapBack() {
		ShaderLineMapping mapping = ShaderLineMapping.identity()
				.addInsertedLinesAfterSourceLine(3, 2);
		assertEquals(3, mapping.toSourceLine(3));
		assertEquals(-1, mapping.toSourceLine(4));
		assertEquals(-1, mapping.toSourceLine(5));
		assertEquals(4, mapping.toSourceLine(6));
	}

	@Test
	public void multipleInsertionsAccumulate() {
		ShaderLineMapping mapping = ShaderLineMapping.withLeadingInsertedLines(1)
				.addInsertedLinesAfterSourceLine(2, 3);
		assertEquals(-1, mapping.toSourceLine(1));
		assertEquals(2, mapping.toSourceLine(3));
		assertEquals(-1, mapping.toSourceLine(4));
		assertEquals(-1, mapping.toSourceLine(6));
		assertEquals(3, mapping.toSourceLine(7));
	}

	@Test
	public void nonPositiveInsertionReturnsSameMapping() {
		ShaderLineMapping mapping = ShaderLineMapping.identity();
		assertSame(mapping, mapping.addInsertedLinesAfterSourceLine(1, 0));
		assertSame(mapping, mapping.addInsertedLinesAfterSourceLine(1, -2));
	}
}
