package de.markusfisch.android.shadereditor.hardware;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;

import org.junit.Test;

public class CameraOrientationTest {
	private static final int ROTATION_0 = 0;
	private static final int ROTATION_90 = 1;
	private static final int ROTATION_180 = 2;
	private static final int ROTATION_270 = 3;

	// Clockwise rotation of the sensor buffer that is upright on the display,
	// per display rotation 0, 90, 180, 270. The back lens turns with the
	// device, the front lens sees it turn the other way.
	private static final int[] BACK_90 = {90, 0, 270, 180};
	private static final int[] BACK_270 = {270, 180, 90, 0};
	private static final int[] FRONT_90 = {90, 180, 270, 0};
	private static final int[] FRONT_270 = {270, 0, 90, 180};

	@Test
	public void backLensSensorAt90IsUprightInEveryRotation() {
		assertUprightInEveryRotation(90, false, BACK_90);
	}

	@Test
	public void backLensSensorAt270IsUprightInEveryRotation() {
		assertUprightInEveryRotation(270, false, BACK_270);
	}

	@Test
	public void frontLensSensorAt90IsUprightInEveryRotation() {
		assertUprightInEveryRotation(90, true, FRONT_90);
	}

	@Test
	public void frontLensSensorAt270IsUprightInEveryRotation() {
		assertUprightInEveryRotation(270, true, FRONT_270);
	}

	@Test
	public void frontLensSensorAt270InPortraitIsUprightAndUnmirrored() {
		// Most phones mount the front sensor at 270 degrees. The former
		// table assumed 90 and showed this case upside down.
		float[] matrix = new float[4];
		float[] addent = new float[2];
		CameraOrientation.compute(270, true, ROTATION_0, matrix, addent);
		// The buffer is turned 270 degrees clockwise: its first row runs
		// up the left edge of the view, its first column along the bottom.
		assertSample(matrix, addent, 0f, 0f, 0f, 0f);
		assertSample(matrix, addent, 0f, 1f, 1f, 0f);
		assertSample(matrix, addent, 1f, 0f, 0f, 1f);
		assertEquals(-1f, determinant(matrix), 0f);
	}

	@Test
	public void backLensSensorAt90MatchesTheFormerTable() {
		// Shaders written against the back camera render as before.
		assertResult(90, false, ROTATION_0,
				new float[]{0f, -1f, -1f, 0f}, new float[]{1f, 1f});
		assertResult(90, false, ROTATION_90,
				new float[]{1f, 0f, 0f, -1f}, new float[]{0f, 1f});
		assertResult(90, false, ROTATION_180,
				new float[]{0f, 1f, 1f, 0f}, new float[]{0f, 0f});
		assertResult(90, false, ROTATION_270,
				new float[]{-1f, 0f, 0f, 1f}, new float[]{1f, 0f});
	}

	private static void assertUprightInEveryRotation(
			int sensorDegrees,
			boolean front,
			int[] expectedDegrees) {
		for (int rotation = ROTATION_0; rotation <= ROTATION_270; ++rotation) {
			float[] matrix = new float[4];
			float[] addent = new float[2];
			CameraOrientation.compute(sensorDegrees, front, rotation,
					matrix, addent);
			assertTurnedClockwise(expectedDegrees[rotation], matrix, addent);
		}
	}

	// The view shows the buffer turned clockwise by degrees, unmirrored:
	// check where the top left, top right and bottom left corners of the
	// view sample the buffer (s to the right, t down from row zero).
	private static void assertTurnedClockwise(
			int degrees,
			@NonNull float[] matrix,
			@NonNull float[] addent) {
		float[][] corners = switch (degrees) {
			case 0 -> new float[][]{{0, 0}, {1, 0}, {0, 1}};
			case 90 -> new float[][]{{0, 1}, {0, 0}, {1, 1}};
			case 180 -> new float[][]{{1, 1}, {0, 1}, {1, 0}};
			case 270 -> new float[][]{{1, 0}, {1, 1}, {0, 0}};
			default -> throw new IllegalArgumentException();
		};
		String message = degrees + " degrees";
		assertSample(message, matrix, addent, 0f, 1f, corners[0]);
		assertSample(message, matrix, addent, 1f, 1f, corners[1]);
		assertSample(message, matrix, addent, 0f, 0f, corners[2]);
		// uv runs up, t runs down: an unmirrored image flips that axis once.
		assertEquals(message, -1f, determinant(matrix), 0f);
	}

	private static void assertResult(
			int sensorDegrees,
			boolean front,
			int rotation,
			@NonNull float[] expectedMatrix,
			@NonNull float[] expectedAddent) {
		float[] matrix = new float[4];
		float[] addent = new float[2];
		CameraOrientation.compute(sensorDegrees, front, rotation, matrix, addent);
		assertArrayEquals(expectedMatrix, matrix, 0f);
		assertArrayEquals(expectedAddent, addent, 0f);
	}

	private static void assertSample(
			@NonNull float[] matrix,
			@NonNull float[] addent,
			float u,
			float v,
			float s,
			float t) {
		assertSample("", matrix, addent, u, v, new float[]{s, t});
	}

	// st = addent + uv * matrix, as GLSL multiplies a row vector with a
	// column-major mat2.
	private static void assertSample(
			String message,
			@NonNull float[] matrix,
			@NonNull float[] addent,
			float u,
			float v,
			@NonNull float[] expected) {
		float s = addent[0] + u * matrix[0] + v * matrix[1];
		float t = addent[1] + u * matrix[2] + v * matrix[3];
		assertArrayEquals(message, expected, new float[]{s, t}, 0f);
	}

	private static float determinant(@NonNull float[] matrix) {
		return matrix[0] * matrix[3] - matrix[1] * matrix[2];
	}
}