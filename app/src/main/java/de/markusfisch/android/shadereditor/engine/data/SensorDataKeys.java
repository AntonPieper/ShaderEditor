package de.markusfisch.android.shadereditor.engine.data;

public final class SensorDataKeys {
	/**
	 * Provides the device's rotation matrix (4x4) relative to the world coordinate system.
	 * This matrix is suitable for use in OpenGL.
	 * It's calculated primarily from Gravity/Magnetic sensors and falls back to the Rotation
	 * Vector sensor.
	 */
	public static final DataKey<float[]> ROTATION_MATRIX = DataKey.of(
			"sensor.rotationMatrix", float[].class);
	/**
	 * Provides the raw gravity vector (float[3]) from the gravity sensor.
	 * Values represent acceleration forces along the x, y, and z axes.
	 */
	public static final DataKey<float[]> GRAVITY = DataKey.of("sensor.gravity", float[].class);
	/**
	 * Provides the raw geomagnetic field vector (float[3]) from the magnetic field sensor.
	 * Values represent the ambient magnetic field along the x, y, and z axes.
	 */
	public static final DataKey<float[]> GEOMAGNETIC = DataKey.of(
			"sensor.geomagnetic", float[].class);
	/**
	 * Provides the inclination matrix (4x4). When using Gravity/Magnetic sensors, this is the
	 * matrix that transforms the magnetic field vector into the same coordinate space as the
	 * gravity vector. When using the Rotation Vector sensor, this will be an identity matrix
	 * as the concept does not directly apply.
	 */
	public static final DataKey<float[]> INCLINATION_MATRIX = DataKey.of(
			"sensor.inclinationMatrix", float[].class);
	/**
	 * Provides the raw data from the Rotation Vector sensor.
	 * The components are (x*sin(θ/2), y*sin(θ/2), z*sin(θ/2), cos(θ/2), optional heading
	 * accuracy).
	 */
	public static final DataKey<float[]> ROTATION_VECTOR = DataKey.of(
			"sensor.rotationVector", float[].class);

	private SensorDataKeys() {
		throw new UnsupportedOperationException(
				"This is a utility class and cannot be instantiated");
	}
}