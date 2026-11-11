package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class ShaderErrorTest {
	private static final String DRIVER_LINE =
			"ERROR: 0:12: 'foo' : undeclared identifier";

	@Test
	public void parseExtractsLineAndMessage() {
		ShaderError error = ShaderError.parse(DRIVER_LINE);
		assertTrue(error.hasLine());
		assertEquals(12, error.getLine());
		assertEquals("'foo' : undeclared identifier", error.getMessage());
	}

	@Test
	public void parseAppliesSilentlyAddedLines() {
		ShaderError error = ShaderError.parse(DRIVER_LINE, 2);
		assertEquals(10, error.getLine());
	}

	@Test
	public void parseAppliesLineMapping() {
		ShaderLineMapping mapping = ShaderLineMapping.identity()
				.addInsertedLinesAfterSourceLine(5, 3);
		assertEquals(9, ShaderError.parse(DRIVER_LINE, mapping).getLine());
	}

	@Test
	public void errorInInsertedLinesHasNoLine() {
		ShaderError error = ShaderError.parse(
				DRIVER_LINE,
				ShaderLineMapping.withLeadingInsertedLines(20));
		assertFalse(error.hasLine());
		assertEquals(-1, error.getLine());
	}

	@Test
	public void unparseableTextBecomesGeneralError() {
		ShaderError error = ShaderError.parse("link failed");
		assertFalse(error.hasLine());
		assertEquals("link failed", error.getMessage());
		assertEquals(ShaderError.createGeneral("link failed"), error);
	}

	@Test
	public void parseAllParsesEachLine() {
		List<ShaderError> errors = ShaderError.parseAll(
				DRIVER_LINE + "\nERROR: 1 compilation errors.");
		assertEquals(2, errors.size());
		assertEquals(12, errors.get(0).getLine());
		assertFalse(errors.get(1).hasLine());
	}
}
