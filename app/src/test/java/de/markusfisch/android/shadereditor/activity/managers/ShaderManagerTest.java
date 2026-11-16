package de.markusfisch.android.shadereditor.activity.managers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.markusfisch.android.shadereditor.database.DataRecords;

public class ShaderManagerTest {
	@Test
	public void armsCrashGuardForSavedShaderRenderedInBackground() {
		assertEquals(42, ShaderManager.getPendingCrashShaderId(true, 42, 0));
	}

	// A shader that drew a frame is saved: the app ended after that,
	// as by an update, did not end on a shader that was starting.
	@Test
	public void leavesCrashGuardOffForShaderThatDrew() {
		assertEquals(0, ShaderManager.getPendingCrashShaderId(true, 42, 42));
		assertEquals(42, ShaderManager.getPendingCrashShaderId(true, 42, 7));
	}

	@Test
	public void leavesCrashGuardOffForUnsavedShader() {
		assertEquals(0, ShaderManager.getPendingCrashShaderId(true, 0, 0));
	}

	@Test
	public void leavesCrashGuardOffWithoutBackgroundPreview() {
		assertEquals(0, ShaderManager.getPendingCrashShaderId(false, 42, 0));
	}

	@Test
	public void titlesShaderByNameThenDateThenNumber() {
		assertEquals("Plasma", ShaderManager.getTitle(
				new DataRecords.Shader(42, "", "Plasma", "2026-11-12", 1f)));
		assertEquals("2026-11-12", ShaderManager.getTitle(
				new DataRecords.Shader(42, "", null, "2026-11-12", 1f)));
		assertEquals("Shader 42", ShaderManager.getTitle(
				new DataRecords.Shader(42, "", null, null, 1f)));
	}

	@Test
	public void copiesDeletesAndInstallsOnlyStoredShaders() {
		assertTrue(ShaderManager.can(OpenItem.Action.SAVE, 0));
		assertTrue(ShaderManager.can(OpenItem.Action.SHARE, 0));
		for (var action : List.of(OpenItem.Action.SAVE_COPY,
				OpenItem.Action.DELETE,
				OpenItem.Action.WALLPAPER)) {
			assertFalse(ShaderManager.can(action, 0));
			assertTrue(ShaderManager.can(action, 42));
		}
		// What is open is what runs.
		assertFalse(ShaderManager.can(OpenItem.Action.RUN_THIS_FILE, 42));
	}
}
