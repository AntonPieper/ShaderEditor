package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.LifecycleOwner;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

	@NonNull
	private static Map<String, GlUniform> program(Object... nameLocationPairs) {
		var uniforms = new HashMap<String, GlUniform>();
		for (int i = 0; i < nameLocationPairs.length; i += 2) {
			String name = (String) nameLocationPairs[i];
			int location = (Integer) nameLocationPairs[i + 1];
			uniforms.put(name, new GlUniform(name, 0, 1, location));
		}
		return uniforms;
	}
}
