package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.R;
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
	private static final List<String> events = new ArrayList<>();
	private static boolean magneticAvailable;

	private static class Fake implements UniformSource {
		final String name;

		Fake(String name) {
			this.name = name;
		}

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			events.add("start " + name);
		}

		@Override
		public void stop() {
			events.add("stop " + name);
		}
	}

	private static final UniformSource.Key<Fake> GRAVITY = () -> new Fake("gravity");
	private static final UniformSource.Key<Fake> MAGNETIC = () -> new Fake("magnetic");
	private static final UniformSource.Key<Fake> VECTOR = () -> new Fake("vector");

	private static final class FakeRotation extends Fake {
		static final Key<FakeRotation> KEY = FakeRotation::new;

		Fake gravity;
		Fake magnetic;
		Fake vector;

		FakeRotation() {
			super("rotation");
		}

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			super.start(host, sources);
			gravity = sources.start(GRAVITY);
			magnetic = sources.start(MAGNETIC);
			vector = magneticAvailable ? sources.get(VECTOR) : sources.start(VECTOR);
		}
	}

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

	private static final List<UniformDef> TABLE = List.of(
			new UniformDef("gravity", GlslType.VEC3, 0, 0, GRAVITY, (s, out) -> {
			}),
			new UniformDef("magnetic", GlslType.VEC3, 0, 0, MAGNETIC, (s, out) -> {
			}),
			new UniformDef("rotationVector", GlslType.VEC3, 0, 0, VECTOR, (s, out) -> {
			}),
			new UniformDef("rotationMatrix", GlslType.MAT3, 0, 0, FakeRotation.KEY,
					(s, out) -> {
					}),
			new UniformDef("orientation", GlslType.VEC3, 0, 0, FakeRotation.KEY,
					(s, out) -> {
					}),
			new UniformDef("inclination", GlslType.FLOAT, 0, 0, FakeRotation.KEY,
					(s, out) -> {
					}));

	@Before
	public void reset() {
		events.clear();
		magneticAvailable = true;
	}

	@Test
	public void rotationAloneStartsItsDependenciesOnce() {
		var sources = new UniformSources(HOST);
		link(sources, "rotationMatrix", "orientation", "inclination");
		assertEquals(List.of("start rotation", "start gravity", "start magnetic"), events);

		sources.stop();
		assertStoppedOnce("rotation", "gravity", "magnetic");
		assertEquals(6, events.size());
	}

	@Test
	public void rotationAndGravityShareOneGravity() {
		var sources = new UniformSources(HOST);
		link(sources, "gravity", "rotationMatrix");
		var rotation = sources.get(FakeRotation.KEY);
		assertSame(sources.get(GRAVITY), rotation.gravity);
		assertEquals(1, count("start gravity"));

		sources.stop();
		assertStoppedOnce("rotation", "gravity", "magnetic");
	}

	@Test
	public void missingMagneticStartsOneRotationVector() {
		magneticAvailable = false;
		var sources = new UniformSources(HOST);
		link(sources, "rotationVector", "rotationMatrix");
		var rotation = sources.get(FakeRotation.KEY);
		assertSame(sources.get(VECTOR), rotation.vector);
		assertEquals(1, count("start vector"));

		sources.stop();
		assertStoppedOnce("rotation", "gravity", "magnetic", "vector");
	}

	@Test
	public void rotationVectorStartsOnlyWhenNeeded() {
		var sources = new UniformSources(HOST);
		link(sources, "rotationMatrix");
		assertNull(sources.get(FakeRotation.KEY).vector);
		assertEquals(0, count("start vector"));
		sources.stop();

		link(sources, "rotationVector", "rotationMatrix");
		assertSame(sources.get(VECTOR), sources.get(FakeRotation.KEY).vector);
	}

	@Test
	public void rotationVectorPrecedesRotationEntries() {
		var builtins = UniformTable.BUILTINS;
		int vector = builtins.indexOf(UniformTable.get("rotationVector"));
		for (var name : List.of("rotationMatrix", "orientation",
				"inclinationMatrix", "inclination")) {
			assertTrue(vector < builtins.indexOf(UniformTable.get(name)));
			assertSame(Rotation.KEY, UniformTable.get(name).getSource());
		}
		assertSame(RotationVector.KEY, UniformTable.get("rotationVector").getSource());
	}

	@Test
	public void rotationIsUnavailableWithoutListeners() {
		var rotation = rotation();
		rotation.update(1L);
		assertFalse(rotation.available);
		assertFalse(rotation.inclined);
	}

	@Test
	public void accelerometerGravityDoesNotFeedRotation() {
		var rotation = rotation();
		rotation.gravity.values = new float[]{0f, 0f, 9.81f};
		rotation.magnetic.values = new float[]{0f, 20f, -40f};
		rotation.magnetic.filtered = new float[]{0f, 20f, -40f};
		rotation.update(1L);
		assertFalse(rotation.available);
		assertFalse(rotation.inclined);
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
		var rotation = rotation();
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

	@NonNull
	private static Rotation rotation() {
		var rotation = new Rotation();
		rotation.gravity = new Gravity();
		rotation.magnetic = new Magnetic();
		return rotation;
	}

	private static void link(@NonNull UniformSources sources, String... names) {
		Map<String, GlUniform> uniforms = new HashMap<>();
		for (int i = 0; i < names.length; ++i) {
			uniforms.put(names[i], UniformSourcesTest.declared(TABLE, names[i], i));
		}
		new ActiveBuiltins(TABLE, uniforms, sources,
				new LinkOutput());
	}

	private static int count(String event) {
		int count = 0;
		for (var e : events) {
			if (e.equals(event)) {
				++count;
			}
		}
		return count;
	}

	private static void assertStoppedOnce(String... names) {
		for (var name : names) {
			assertEquals(1, count("start " + name));
			assertEquals(1, count("stop " + name));
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
