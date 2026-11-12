package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

public class UniformSourcesTest {
	private static final List<String> events = new ArrayList<>();

	private static final class FakeSource implements UniformSource {
		static final Key<FakeSource> KEY = FakeSource::new;

		int starts;
		int stops;
		EngineHost host;
		long updatedAt;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			++starts;
			this.host = host;
			events.add("start");
		}

		@Override
		public void stop() {
			++stops;
			events.add("stop");
		}

		@Override
		public void update(long now) {
			updatedAt = now;
			events.add("update");
		}
	}

	private static final class LocatedOutput extends UniformTableTest.RecordingOutput
			implements ActiveBuiltins.Output {
		final List<Integer> locations = new ArrayList<>();

		@Override
		public void begin() {
			events.add("begin");
		}

		@Override
		public void setLocation(int location) {
			locations.add(location);
		}

		@Override
		void record(String call, float[] value) {
			super.record(call, value);
			events.add("write");
		}
	}

	static final EngineHost HOST = new EngineHost() {
		@NonNull
		@Override
		public Context context() {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean onPermissionNeeded(@NonNull String permission) {
			return false;
		}

		@Override
		public boolean onNotificationAccessNeeded() {
			return false;
		}

		@Override
		public LifecycleOwner cameraOwner() {
			return null;
		}
	};

	private static final List<UniformDef> TABLE = List.of(
			new UniformDef("first", GlslType.FLOAT, 0, 0, FakeSource.KEY,
					(fake, out) -> out.setFloat(fake.updatedAt)),
			new UniformDef("second", GlslType.INT, 0, 0, FakeSource.KEY,
					(fake, out) -> out.setInt(fake.starts)),
			new UniformDef("plain", GlslType.FLOAT, 0, 0,
					(in, out) -> out.setFloat(in.time)),
			new UniformDef("declared", GlslType.SAMPLER_2D, 0, FakeSource.KEY));

	@Test
	public void sharedSourceStartsOnceAndStops() {
		events.clear();
		var sources = new UniformSources(HOST);
		var output = new LocatedOutput();
		var builtins = new ActiveBuiltins(TABLE,
				program("first", 3, "second", 5), sources, output);
		assertEquals(List.of("start"), events);

		builtins.write(new FrameInputs(), 42L);
		assertEquals(List.of(3, 5), output.locations);
		assertEquals(List.of("float", "int"), output.calls);
		assertEquals(42f, output.values.get(0)[0], 0f);
		assertEquals(1f, output.values.get(1)[0], 0f);

		sources.stop();
		assertEquals(
				List.of("start", "update", "begin", "write", "write", "stop"),
				events);
	}

	@Test
	public void sourceStartsWithHostOnlyOnce() {
		var sources = new UniformSources(HOST);
		var first = (FakeSource) sources.start(FakeSource.KEY);
		assertSame(first, sources.start(FakeSource.KEY));
		assertEquals(1, first.starts);
		assertSame(HOST, first.host);
		sources.stop();
		assertEquals(1, first.stops);
		var second = (FakeSource) sources.start(FakeSource.KEY);
		assertEquals(1, second.starts);
		assertEquals(1, first.stops);
	}

	@Test
	public void unusedSourceNeverStarts() {
		events.clear();
		var sources = new UniformSources(HOST);
		var output = new LocatedOutput();
		var builtins = new ActiveBuiltins(TABLE,
				program("plain", 1, "other", 2), sources, output);
		builtins.write(new FrameInputs(), 7L);
		sources.stop();
		assertEquals(List.of("begin", "write"), events);
		assertEquals(List.of(1), output.locations);
	}

	@Test
	public void inactiveLocationDoesNotStartSource() {
		events.clear();
		var sources = new UniformSources(HOST);
		new ActiveBuiltins(TABLE, program("first", -1), sources, new LocatedOutput());
		assertEquals(List.of(), events);
	}

	@Test
	public void declarationStartsSourceButIsNeverWritten() {
		events.clear();
		var sources = new UniformSources(HOST);
		var output = new LocatedOutput();
		var builtins = new ActiveBuiltins(TABLE,
				program("declared", 4), sources, output);
		assertEquals(List.of("start"), events);
		builtins.write(new FrameInputs(), 9L);
		assertEquals(List.of("start", "update", "begin"), events);
		assertEquals(List.of(), output.locations);
	}

	@Test
	public void cameraSamplerAloneStartsCameraSource() {
		var sources = new UniformSources(HOST);
		var output = new LocatedOutput();
		var builtins = new ActiveBuiltins(UniformTable.BUILTINS,
				program("cameraFront", 0), sources, output);
		var camera = sources.get(CameraSource.KEY);
		assertNotNull(camera);
		assertFalse(camera.open);
		builtins.write(new FrameInputs(), 1L);
		assertEquals(List.of(), output.locations);
		assertEquals(List.of(), output.calls);
		sources.stop();
	}

	@Test
	public void cameraOnAHostWithoutOwnerWarnsUntilItStops() {
		var sources = new UniformSources(HOST);
		new ActiveBuiltins(UniformTable.BUILTINS,
				program("cameraBack", 0), sources, new LocatedOutput());
		assertEquals(1, sources.getWarnings().size());
		assertEquals(ProjectDiagnostic.Severity.WARNING,
				sources.getWarnings().get(0).severity());
		sources.stop();
		assertEquals(List.of(), sources.getWarnings());
	}

	@Test
	public void warningAfterTheStartIsReportedOnce() {
		var sources = new UniformSources(HOST);
		sources.warn("no camera");
		assertTrue(sources.takeWarned());
		assertFalse(sources.takeWarned());
		sources.warn("no camera");
		assertFalse(sources.takeWarned());
		sources.warn("The front camera cannot be opened");
		assertTrue(sources.takeWarned());
		assertEquals(2, sources.getWarnings().size());
		sources.warn("again");
		sources.stop();
		assertFalse(sources.takeWarned());
	}

	@Test
	public void sourcesTheNextShaderStillReadsKeepRunning() {
		events.clear();
		var sources = new UniformSources(HOST);
		new ActiveBuiltins(TABLE, program("first", 1), sources, new LocatedOutput());
		var running = sources.get(FakeSource.KEY);
		new ActiveBuiltins(TABLE, program("second", 2), sources, new LocatedOutput());
		assertSame(running, sources.get(FakeSource.KEY));
		assertEquals(List.of("start"), events);
	}

	@Test
	public void sourcesTheNextShaderNoLongerReadsStop() {
		events.clear();
		var sources = new UniformSources(HOST);
		new ActiveBuiltins(TABLE, program("first", 1), sources, new LocatedOutput());
		new ActiveBuiltins(TABLE, program("plain", 2), sources, new LocatedOutput());
		assertNull(sources.get(FakeSource.KEY));
		assertEquals(List.of("start", "stop"), events);
	}

	@Test
	public void sourcesStopWithoutAShaderAndStartAgainWithOne() {
		events.clear();
		var sources = new UniformSources(HOST);
		new ActiveBuiltins(TABLE, program("first", 1), sources, new LocatedOutput());
		// What the engine does on context loss, detach and pause.
		sources.stop();
		assertNull(sources.get(FakeSource.KEY));
		new ActiveBuiltins(TABLE, program("first", 1), sources, new LocatedOutput());
		assertNotNull(sources.get(FakeSource.KEY));
		assertEquals(List.of("start", "stop", "start"), events);
	}

	@Test
	public void anotherCameraBindingStartsTheSourcesAgain() {
		events.clear();
		var sources = new UniformSources(HOST);
		new ActiveBuiltins(TABLE, program("first", 1), sources, new LocatedOutput());
		sources.setCameraBinding(new CameraSource.Binding(
				UniformTable.CAMERA_BACK, new GlExternalTexture(1)));
		new ActiveBuiltins(TABLE, program("first", 1), sources, new LocatedOutput());
		assertEquals(List.of("start", "stop", "start"), events);
	}

	@NonNull
	private static Map<String, GlUniform> program(Object... nameLocationPairs) {
		var uniforms = new HashMap<String, GlUniform>();
		for (int i = 0; i < nameLocationPairs.length; i += 2) {
			String name = (String) nameLocationPairs[i];
			int location = (Integer) nameLocationPairs[i + 1];
			uniforms.put(name, declared(TABLE, name, location));
		}
		return uniforms;
	}

	// The uniform a program declares as the table entry of that name declares it.
	@NonNull
	static GlUniform declared(List<UniformDef> table, String name, int location) {
		for (var def : table) {
			if (def.getName().equals(name)) {
				return new GlUniform(name, glType(def.getType()), 1, location);
			}
		}
		for (var def : UniformTable.BUILTINS) {
			if (def.getName().equals(name)) {
				return new GlUniform(name, glType(def.getType()), 1, location);
			}
		}
		return new GlUniform(name, GLES20.GL_FLOAT, 1, location);
	}

	static int glType(@NonNull GlslType type) {
		return switch (type) {
			case INT -> GLES20.GL_INT;
			case FLOAT -> GLES20.GL_FLOAT;
			case VEC2 -> GLES20.GL_FLOAT_VEC2;
			case VEC3 -> GLES20.GL_FLOAT_VEC3;
			case VEC4 -> GLES20.GL_FLOAT_VEC4;
			case MAT2 -> GLES20.GL_FLOAT_MAT2;
			case MAT3 -> GLES20.GL_FLOAT_MAT3;
			case SAMPLER_2D -> GLES20.GL_SAMPLER_2D;
			case SAMPLER_EXTERNAL_OES -> GLES11Ext.GL_SAMPLER_EXTERNAL_OES;
		};
	}
}
