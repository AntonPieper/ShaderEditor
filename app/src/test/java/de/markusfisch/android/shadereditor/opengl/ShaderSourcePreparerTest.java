package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

public class ShaderSourcePreparerTest {
	@Test
	public void readsTheFTimePeriod() {
		assertEquals(3f, ShaderSourcePreparer.parseFTime(null), 0f);
		assertEquals(3f, ShaderSourcePreparer.parseFTime("void main() {}"), 0f);
		assertEquals(2.5f, ShaderSourcePreparer.parseFTime(
				"#define FTIME_PERIOD 2.5\n"), 0f);
		assertEquals(4f, ShaderSourcePreparer.parseFTime(
				"#define FTIME_PERIOD 4.\n"), 0f);
	}

	@Test
	public void malformedFTimePeriodIsNoNumber() {
		assertTrue(Float.isNaN(ShaderSourcePreparer.parseFTime(
				"#define FTIME_PERIOD 1..2\n")));
		assertTrue(Float.isNaN(ShaderSourcePreparer.parseFTime(
				"#define FTIME_PERIOD .\n")));
		assertTrue(Float.isNaN(ShaderSourcePreparer.parseFTime(
				"#define FTIME_PERIOD 1.2.3\n")));
	}

	@Test
	public void malformedFTimePeriodIsALocatedError() {
		var errors = new ArrayList<ProjectDiagnostic>();
		var source = ShaderSourcePreparer.prepare(
				"precision mediump float;\n" +
						"\n" +
						"#define FTIME_PERIOD 1..2\n" +
						"void main() {}\n",
				2,
				errors);
		assertNull(source.getFragmentShader());
		assertEquals(1, errors.size());
		var error = errors.get(0);
		assertEquals(ProjectDiagnostic.Severity.ERROR, error.severity());
		assertEquals(3, error.line());
		assertEquals("FTIME_PERIOD 1..2 is not a number", error.message());
	}

	@Test
	public void validSourceAddsNoErrors() {
		var errors = new ArrayList<ProjectDiagnostic>();
		var source = ShaderSourcePreparer.prepare(
				"#define FTIME_PERIOD 2.\nvoid main() {}\n",
				2,
				errors);
		assertNotNull(source.getFragmentShader());
		assertEquals(2f, source.getFTimeMax(), 0f);
		assertEquals(List.of(), errors);
	}
}
