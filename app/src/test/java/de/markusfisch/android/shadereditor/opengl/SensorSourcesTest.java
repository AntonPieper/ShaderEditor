package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.hardware.AbstractListener;
import de.markusfisch.android.shadereditor.hardware.Sample;
import de.markusfisch.android.shadereditor.hardware.SensorRegistry;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Accelerometer;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Gravity;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Gyroscope;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Light;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Linear;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Magnetic;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Pressure;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Proximity;
import de.markusfisch.android.shadereditor.opengl.SensorSources.Rotation;
import de.markusfisch.android.shadereditor.opengl.SensorSources.RotationVector;

public class SensorSourcesTest {
	private static final float EPSILON = 1e-6f;
	private static final int ACCELEROMETER = Sensor.TYPE_ACCELEROMETER;
	private static final int GRAVITY = Sensor.TYPE_GRAVITY;
	private static final int LINEAR = Sensor.TYPE_LINEAR_ACCELERATION;
	private static final int MAGNETIC = Sensor.TYPE_MAGNETIC_FIELD;
	private static final int ROTATION_VECTOR = Sensor.TYPE_ROTATION_VECTOR;
	private static final EngineHost HOST = new EngineHost() {
		@NonNull
		@Override
		public Context context() {
			throw new UnsupportedOperationException();
		}

		@Override
		public void onPermissionNeeded(@NonNull String permission) {
		}

		@Override
		public LifecycleOwner cameraOwner() {
			return null;
		}
	};

	// Real sources on a device that has only the given sensor types.
	private static final class Device implements SensorRegistry {
		final List<Integer> registrations = new ArrayList<>();
		final Set<Integer> present = new HashSet<>();
		final Map<Integer, AbstractListener> listeners = new HashMap<>();
		final UniformSources sources = new UniformSources(HOST, this);

		Device(int... sensorTypes) {
			for (int type : sensorTypes) {
				present.add(type);
			}
		}

		@Override
		public boolean register(@NonNull SensorEventListener listener, int sensorType) {
			registrations.add(sensorType);
			if (!present.contains(sensorType)) {
				return false;
			}
			listeners.put(sensorType, (AbstractListener) listener);
			return true;
		}

		@Override
		public void unregister(@NonNull SensorEventListener listener) {
		}

		void link(String... names) {
			Map<String, GlUniform> uniforms = new HashMap<>();
			for (int i = 0; i < names.length; ++i) {
				uniforms.put(names[i], UniformSourcesTest.declared(
						UniformTable.BUILTINS, names[i], i));
			}
			new ActiveBuiltins(UniformTable.BUILTINS, uniforms, sources,
					new LinkOutput());
		}

		@NonNull
		Sample sample(int sensorType) {
			return listeners.get(sensorType).sample;
		}

		int registrationsOf(int sensorType) {
			int count = 0;
			for (int registration : registrations) {
				if (registration == sensorType) {
					++count;
				}
			}
			return count;
		}
	}

	@Test
	public void gravityAndLinearShareOneAccelerometer() {
		for (var order : List.of(
				List.of(Gravity.KEY, Linear.KEY),
				List.of(Linear.KEY, Gravity.KEY))) {
			var device = new Device(ACCELEROMETER);
			for (var key : order) {
				device.sources.start(key);
			}
			var accelerometer = device.sources.get(Accelerometer.KEY);
			assertNotNull(accelerometer);
			assertNotNull(accelerometer.gravity);
			assertSame(accelerometer.gravity, device.sources.get(Gravity.KEY).values);
			assertSame(accelerometer.linear, device.sources.get(Linear.KEY).values);
			assertEquals(1, device.registrationsOf(ACCELEROMETER));
			device.sources.stop();
		}
		var device = new Device(ACCELEROMETER);
		device.link("gravity", "linear");
		assertNotNull(device.sources.get(Gravity.KEY).values);
		assertNotNull(device.sources.get(Linear.KEY).values);
	}

	@Test
	public void eachFallbackAloneUsesTheAccelerometer() {
		var gravity = new Device(ACCELEROMETER);
		gravity.link("gravity");
		assertSame(gravity.sources.get(Accelerometer.KEY).gravity,
				gravity.sources.get(Gravity.KEY).values);

		var linear = new Device(ACCELEROMETER);
		linear.link("linear");
		assertSame(linear.sources.get(Accelerometer.KEY).linear,
				linear.sources.get(Linear.KEY).values);
	}

	@Test
	public void sensorsNeedNoAccelerometer() {
		var device = new Device(GRAVITY,
				LINEAR,
				ACCELEROMETER);
		device.link("gravity", "linear");
		assertNotNull(device.sources.get(Gravity.KEY).values);
		assertNotNull(device.sources.get(Linear.KEY).values);
		assertNull(device.sources.get(Accelerometer.KEY));
		assertEquals(0, device.registrationsOf(ACCELEROMETER));
	}

	@Test
	public void gravitySensorAndLinearFallback() {
		var device = new Device(GRAVITY, ACCELEROMETER);
		device.link("gravity", "linear");
		var accelerometer = device.sources.get(Accelerometer.KEY);
		assertNotSame(accelerometer.gravity, device.sources.get(Gravity.KEY).values);
		assertNotNull(device.sources.get(Gravity.KEY).values);
		assertSame(accelerometer.linear, device.sources.get(Linear.KEY).values);
	}

	@Test
	public void noAccelerometerLeavesBothEmpty() {
		var device = new Device();
		device.link("gravity", "linear");
		assertNull(device.sources.get(Gravity.KEY).values);
		assertNull(device.sources.get(Linear.KEY).values);
		assertEquals(1, device.registrationsOf(ACCELEROMETER));
	}

	@Test
	public void linearWithRotationAndNoGravityUniform() {
		for (var order : List.of(
				List.of(Linear.KEY, Rotation.KEY),
				List.of(Rotation.KEY, Linear.KEY))) {
			var device = new Device(ACCELEROMETER,
					MAGNETIC);
			for (var key : order) {
				device.sources.start(key);
			}
			var accelerometer = device.sources.get(Accelerometer.KEY);
			var rotation = device.sources.get(Rotation.KEY);
			assertSame(accelerometer.linear, device.sources.get(Linear.KEY).values);
			assertSame(accelerometer.gravity, rotation.gravity.values);
			assertNull(rotation.vector);
			assertEquals(1, device.registrationsOf(ACCELEROMETER));
			device.sources.stop();
		}
		var device = new Device(ACCELEROMETER, MAGNETIC);
		device.link("linear", "orientation");
		assertNotNull(device.sources.get(Linear.KEY).values);
		assertNotNull(device.sources.get(Rotation.KEY).gravity.values);
	}

	@Test
	public void rotationWithAllSensorsStartsNoRotationVector() {
		var device = new Device(GRAVITY, MAGNETIC,
				ACCELEROMETER, ROTATION_VECTOR);
		device.link("rotationMatrix", "orientation", "inclination");
		var rotation = device.sources.get(Rotation.KEY);
		assertSame(device.sources.get(Gravity.KEY), rotation.gravity);
		assertNull(rotation.vector);
		assertNull(device.sources.get(RotationVector.KEY));
		assertNull(device.sources.get(Accelerometer.KEY));
	}

	@Test
	public void missingMagneticFallsBackToRotationVector() {
		var device = new Device(GRAVITY, ROTATION_VECTOR);
		device.link("rotationMatrix");
		var rotation = device.sources.get(Rotation.KEY);
		assertSame(device.sources.get(RotationVector.KEY), rotation.vector);
		assertNotNull(rotation.vector.values);
	}

	@Test
	public void missingGravityFallsBackToRotationVector() {
		var device = new Device(MAGNETIC, ROTATION_VECTOR);
		device.link("orientation");
		var rotation = device.sources.get(Rotation.KEY);
		assertNull(rotation.gravity.values);
		assertNotNull(rotation.vector);
		assertNotNull(rotation.vector.values);
	}

	@Test
	public void rotationAndRotationVectorShareOneSourceInAnyOrder() {
		for (var order : List.of(
				List.of(RotationVector.KEY, Rotation.KEY),
				List.of(Rotation.KEY, RotationVector.KEY))) {
			var device = new Device(ROTATION_VECTOR);
			for (var key : order) {
				device.sources.start(key);
			}
			assertSame(device.sources.get(RotationVector.KEY),
					device.sources.get(Rotation.KEY).vector);
			assertEquals(1, device.registrationsOf(ROTATION_VECTOR));
		}
	}

	@Test
	public void rotationIsUnavailableWithoutSensors() {
		var device = new Device();
		device.link("rotationMatrix");
		var rotation = device.sources.get(Rotation.KEY);
		rotation.update(1L);
		assertFalse(rotation.available);
		assertFalse(rotation.inclined);
	}

	@Test
	public void framesSeeGravityAndLinearFromOneAccelerometerSample() throws Exception {
		var device = new Device(ACCELEROMETER);
		device.link("gravity", "linear");
		var gravity = device.sources.get(Gravity.KEY);
		var linear = device.sources.get(Linear.KEY);
		var sample = device.sample(ACCELEROMETER);
		var done = new AtomicBoolean();
		var publisher = new Thread(() -> {
			float[] values = new float[6];
			for (int i = 1; !done.get(); ++i) {
				Arrays.fill(values, i);
				sample.publish(values);
			}
		});
		publisher.start();
		try {
			for (int frame = 0; frame < 100000; ++frame) {
				device.sources.update(frame);
				float value = gravity.values[0];
				assertArrayEquals(new float[]{value, value, value}, gravity.values, 0f);
				assertArrayEquals(new float[]{value, value, value}, linear.values, 0f);
			}
		} finally {
			done.set(true);
			publisher.join();
		}
	}

	@Test
	public void sampleIsCapturedOnlyByUpdate() {
		var device = new Device(GRAVITY, LINEAR, MAGNETIC,
				Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GAME_ROTATION_VECTOR,
				Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE, Sensor.TYPE_PROXIMITY);
		device.link("gravity", "linear", "magnetic", "gyroscope", "rotationVector",
				"light", "pressure", "proximity");
		for (int type : device.listeners.keySet()) {
			device.sample(type).publish(new float[]{1f, 2f, 3f, 4f, 5f, 6f});
		}
		device.sources.update(1L);
		for (int type : device.listeners.keySet()) {
			device.sample(type).publish(new float[]{7f, 8f, 9f, 10f, 11f, 12f});
		}

		float[] vector = {1f, 2f, 3f};
		assertArrayEquals(vector, device.sources.get(Gravity.KEY).values, 0f);
		assertArrayEquals(vector, device.sources.get(Linear.KEY).values, 0f);
		assertArrayEquals(vector, device.sources.get(Gyroscope.KEY).values, 0f);
		assertArrayEquals(vector, device.sources.get(RotationVector.KEY).values, 0f);
		var magnetic = device.sources.get(Magnetic.KEY);
		assertArrayEquals(vector, magnetic.values, 0f);
		assertArrayEquals(new float[]{4f, 5f, 6f}, magnetic.filtered, 0f);
		assertEquals(1f, device.sources.get(Light.KEY).ambient, 0f);
		assertEquals(1f, device.sources.get(Pressure.KEY).pressure, 0f);
		assertEquals(1f, device.sources.get(Proximity.KEY).centimeters, 0f);

		device.sources.update(2L);
		assertArrayEquals(new float[]{10f, 11f, 12f}, magnetic.filtered, 0f);
		assertEquals(7f, device.sources.get(Light.KEY).ambient, 0f);
	}

	// SensorManager is not available here, so this checks what Rotation
	// derives from: the gravity and field its dependencies captured.
	@Test
	public void rotationDerivesFromTheFramesCapturedSet() {
		var device = new Device(GRAVITY, MAGNETIC);
		var rotation = device.sources.start(Rotation.KEY);
		device.sample(GRAVITY).publish(new float[]{1f, 2f, 3f});
		device.sample(MAGNETIC).publish(new float[]{4f, 5f, 6f, 7f, 8f, 9f});
		rotation.gravity.update(1L);
		rotation.magnetic.update(1L);
		device.sample(GRAVITY).publish(new float[]{10f, 11f, 12f});
		device.sample(MAGNETIC).publish(new float[]{13f, 14f, 15f, 16f, 17f, 18f});

		assertArrayEquals(new float[]{1f, 2f, 3f}, rotation.gravity.values, 0f);
		assertArrayEquals(new float[]{7f, 8f, 9f}, rotation.magnetic.filtered, 0f);
	}

	@Test
	public void writesVectorSensors() {
		var gravity = new Gravity();
		assertNotWritten("gravity", gravity);
		gravity.values = new float[]{1f, 2f, 3f};
		assertWritten("gravity", gravity, "vec3", 1f, 2f, 3f);

		var linear = new Linear();
		assertNotWritten("linear", linear);
		linear.values = new float[]{4f, 5f, 6f};
		assertWritten("linear", linear, "vec3", 4f, 5f, 6f);

		var gyroscope = new Gyroscope();
		assertNotWritten("gyroscope", gyroscope);
		gyroscope.values = new float[]{1f, 1f, 1f};
		assertWritten("gyroscope", gyroscope, "vec3", 1f, 1f, 1f);

		var magnetic = new Magnetic();
		assertNotWritten("magnetic", magnetic);
		magnetic.values = new float[]{7f, 8f, 9f};
		magnetic.filtered = new float[]{0f, 0f, 0f};
		assertWritten("magnetic", magnetic, "vec3", 7f, 8f, 9f);

		var vector = new RotationVector();
		assertNotWritten("rotationVector", vector);
		vector.values = new float[]{.1f, .2f, .3f};
		assertWritten("rotationVector", vector, "vec3", .1f, .2f, .3f);
	}

	@Test
	public void writesScalarSensors() {
		var light = new Light();
		light.ambient = 120f;
		assertNotWritten("light", light);
		light.registered = true;
		assertWritten("light", light, "float", 120f);

		var pressure = new Pressure();
		pressure.pressure = 1013f;
		assertNotWritten("pressure", pressure);
		pressure.registered = true;
		assertWritten("pressure", pressure, "float", 1013f);

		var proximity = new Proximity();
		proximity.centimeters = 5f;
		assertNotWritten("proximity", proximity);
		proximity.registered = true;
		assertWritten("proximity", proximity, "float", 5f);
	}

	@Test
	public void shaderSeesRotationMatricesRowByRow() {
		var device = new Device();
		var rotation = device.sources.start(Rotation.KEY);
		for (var name : List.of("rotationMatrix", "orientation",
				"inclinationMatrix", "inclination")) {
			assertNotWritten(name, rotation);
		}

		// SensorManager's row-major matrices; a shader indexes m[column][row].
		float[] matrix = {1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f, 9f};
		System.arraycopy(matrix, 0, rotation.matrix, 0, 9);
		rotation.available = true;
		assertShaderSees(matrix, uploadedMatrix("rotationMatrix", rotation));
		assertNotWritten("inclinationMatrix", rotation);
		assertNotWritten("inclination", rotation);

		float[] inclination = {9f, 7f, 8f, 6f, 5f, 4f, 3f, 1f, 2f};
		System.arraycopy(inclination, 0, rotation.inclinationMatrix, 0, 9);
		rotation.inclined = true;
		assertShaderSees(inclination, uploadedMatrix("inclinationMatrix", rotation));
		assertShaderSees(matrix, uploadedMatrix("rotationMatrix", rotation));
	}

	@Test
	public void describesSensorDeclarations() {
		assertDeclared("gravity", "vec3", R.string.gravity_vector);
		assertDeclared("gyroscope", "vec3", R.string.gyroscope);
		assertDeclared("inclination", "float", R.string.device_inclination);
		assertDeclared("inclinationMatrix", "mat3", R.string.device_inclination_matrix);
		assertDeclared("light", "float", R.string.light);
		assertDeclared("linear", "vec3", R.string.linear_acceleration_vector);
		assertDeclared("magnetic", "vec3", R.string.magnetic_field);
		assertDeclared("orientation", "vec3", R.string.device_orientation);
		assertDeclared("pressure", "float", R.string.pressure);
		assertDeclared("proximity", "float", R.string.proximity);
		assertDeclared("rotationMatrix", "mat3", R.string.device_rotation_matrix);
		assertDeclared("rotationVector", "vec3", R.string.device_rotation_vector);
	}

	@NonNull
	private static float[] uploadedMatrix(String name, UniformSource source) {
		var out = new UniformTableTest.RecordingOutput();
		UniformTable.get(name).write(new FrameInputs(), source, out);
		assertEquals(List.of("mat3"), out.calls);
		return out.values.get(0);
	}

	// GL reads an untransposed upload column by column: m[column][row].
	private static void assertShaderSees(float[] rows, float[] uploaded) {
		for (int row = 0; row < 3; ++row) {
			for (int column = 0; column < 3; ++column) {
				assertEquals(rows[row * 3 + column], uploaded[column * 3 + row], EPSILON);
			}
		}
	}

	private static void assertNotWritten(String name, UniformSource source) {
		var out = new UniformTableTest.RecordingOutput();
		UniformTable.get(name).write(new FrameInputs(), source, out);
		assertTrue(out.calls.isEmpty());
	}

	private static void assertWritten(
			String name,
			UniformSource source,
			String call,
			float... expected) {
		var out = new UniformTableTest.RecordingOutput();
		UniformTable.get(name).write(new FrameInputs(), source, out);
		assertEquals(List.of(call), out.calls);
		assertArrayEquals(expected, out.values.get(0), EPSILON);
	}

	private static void assertDeclared(String name, String type, int description) {
		var uniform = UniformTable.get(name);
		assertEquals(type, uniform.getType().getKeyword());
		assertEquals(name, uniform.getDeclarator());
		assertEquals(description, uniform.getDescription());
	}

	private static final class LinkOutput
			extends UniformTableTest.RecordingOutput
			implements ActiveBuiltins.Output {
		@Override
		public void begin() {
		}

		@Override
		public void setLocation(int location) {
		}
	}
}
