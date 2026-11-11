package de.markusfisch.android.shadereditor.hardware;

import androidx.annotation.NonNull;

/**
 * The cameraOrientation matrix and cameraAddent vector. A shader computes
 * {@code st = cameraAddent + uv * cameraOrientation} with uv from 0 at the
 * bottom left to 1 at the top right of the view, and st addresses the
 * camera buffer as the sensor delivers it, row zero first. The result shows
 * the camera image upright and unmirrored.
 */
final class CameraOrientation {
	private CameraOrientation() {
	}

	/**
	 * @param sensorDegrees   clockwise rotation that makes the sensor image
	 *                        upright in the device's natural orientation
	 * @param front           whether the lens faces the user
	 * @param displayRotation one of the Surface.ROTATION_* constants
	 * @param matrix          receives the mat2 in column-major order
	 * @param addent          receives the vec2
	 */
	static void compute(
			int sensorDegrees,
			boolean front,
			int displayRotation,
			@NonNull float[] matrix,
			@NonNull float[] addent) {
		// The clockwise rotation that makes the buffer upright on this
		// display. A front lens looks the other way, so it sees the device
		// turn the other way round.
		int displayDegrees = displayRotation * 90;
		int degrees = front
				? sensorDegrees + displayDegrees
				: sensorDegrees - displayDegrees;
		int quarter = ((degrees / 90) % 4 + 4) % 4;
		int cos = quarter == 0 ? 1 : quarter == 2 ? -1 : 0;
		int sin = quarter == 1 ? 1 : quarter == 3 ? -1 : 0;
		// st = addent + (cos * u - sin * v, -sin * u - cos * v)
		matrix[0] = cos;
		matrix[1] = -sin;
		matrix[2] = -sin;
		matrix[3] = -cos;
		addent[0] = (1 - cos + sin) * .5f;
		addent[1] = (1 + sin + cos) * .5f;
	}
}