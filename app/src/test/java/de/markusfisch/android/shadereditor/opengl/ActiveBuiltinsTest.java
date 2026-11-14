package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.opengl.GLES20;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ActiveBuiltinsTest {
	private static final class LocatedOutput extends UniformTableTest.RecordingOutput
			implements ActiveBuiltins.Output {
		final List<Integer> locations = new ArrayList<>();

		@Override
		public void begin() {
		}

		@Override
		public void setLocation(int location) {
			locations.add(location);
		}
	}

	@Test
	public void samplerNamedFrameIsNotWritten() {
		var out = write(Map.of(
				"frame", new GlUniform("frame", GLES20.GL_SAMPLER_2D, 1, 3),
				"second", new GlUniform("second", GLES20.GL_SAMPLER_CUBE, 1, 4),
				"pointerCount",
				new GlUniform("pointerCount", GLES20.GL_SAMPLER_2D, 1, 5)));
		assertEquals(List.of(), out.locations);
		assertEquals(List.of(), out.calls);
	}

	@Test
	public void builtinOfMatchingTypeIsWritten() {
		var out = write(Map.of(
				"frame", new GlUniform("frame", GLES20.GL_INT, 1, 3),
				"powerConnected", new GlUniform("powerConnected", GLES20.GL_BOOL, 1, 4)));
		assertEquals(2, out.locations.size());
		assertEquals(List.of("int", "int"), out.calls);
	}

	@Test
	public void builtinDeclaredAsArrayOrOtherTypeIsNotWritten() {
		var out = write(Map.of(
				"time", new GlUniform("time[0]", GLES20.GL_FLOAT, 2, 1),
				"resolution", new GlUniform("resolution", GLES20.GL_FLOAT_VEC3, 1, 2)));
		assertEquals(List.of(), out.calls);
	}

	@Test
	public void arrayBuiltinTakesAnyArraySize() {
		var in = new FrameInputs();
		in.pointerCount = 1;
		var out = write(in, Map.of(
				"pointers", new GlUniform("pointers[0]", GLES20.GL_FLOAT_VEC3, 4, 6)));
		assertEquals(List.of(6), out.locations);
		assertEquals(List.of("vec3[1]"), out.calls);
	}

	@Test
	public void mouseDeclaredAsVec4IsInPixelsAsShaderToyHasIt() {
		var in = new FrameInputs();
		in.mouseX = 0.5f;
		in.touchX = 10;
		in.touchY = 20;
		in.touchStartX = 1;
		in.touchStartY = 2;
		in.pointerCount = 1;
		var mouse = Map.of("mouse",
				new GlUniform("mouse", GLES20.GL_FLOAT_VEC4, 1, 7));
		var out = write(in, mouse);
		assertEquals(List.of(7), out.locations);
		assertEquals(List.of("vec4"), out.calls);
		assertArrayEquals(new float[]{10, 20, 1, 2}, out.values.get(0), 0f);
		// Released: where it started turns negative.
		in.pointerCount = 0;
		out = write(in, mouse);
		assertArrayEquals(new float[]{10, 20, -1, -2}, out.values.get(0), 0f);
		// The declaration the uniform list shows stays normalized.
		out = write(in, Map.of("mouse",
				new GlUniform("mouse", GLES20.GL_FLOAT_VEC2, 1, 7)));
		assertEquals(List.of("vec2"), out.calls);
		assertEquals(0.5f, out.values.get(0)[0], 0f);
	}

	private static final class CountingSource implements UniformSource {
		static final Key<CountingSource> KEY = CountingSource::new;

		int starts;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			++starts;
		}

		@Override
		public void stop() {
		}
	}

	@Test
	public void programsOfOneBuildShareTheirSourcesAndKeepTheirSlots() {
		var table = List.of(
				new UniformDef("time", GlslType.FLOAT, 0, 0,
						(in, out) -> out.setFloat(in.time)),
				new UniformDef("starts", GlslType.INT, 0, 0, CountingSource.KEY,
						(source, out) -> out.setInt(source.starts)));
		var running = new LocatedOutput();
		var initial = new LocatedOutput();
		var sources = new UniformSources(UniformSourcesTest.HOST);
		var builtins = new ActiveBuiltins(table, List.of(
				Map.of("time", new GlUniform("time", GLES20.GL_FLOAT, 1, 1)),
				Map.of("time", new GlUniform("time", GLES20.GL_FLOAT, 1, 2),
						"starts", new GlUniform("starts", GLES20.GL_INT, 1, 3))),
				sources, List.of(running, initial));
		// Started for the second program, which the first does not read.
		assertEquals(1, sources.get(CountingSource.KEY).starts);
		var in = new FrameInputs();
		in.time = 4;
		builtins.write(in);
		assertEquals(List.of(1), running.locations);
		assertEquals(List.of(), initial.locations);
		in.time = 0;
		builtins.write(1, in);
		assertEquals(List.of(2, 3), initial.locations);
		assertEquals(List.of("float", "int"), initial.calls);
		assertEquals(0f, initial.values.get(0)[0], 0f);
		assertEquals(1f, initial.values.get(1)[0], 0f);
	}

	@NonNull
	private static LocatedOutput write(@NonNull Map<String, GlUniform> uniforms) {
		return write(new FrameInputs(), uniforms);
	}

	@NonNull
	private static LocatedOutput write(
			@NonNull FrameInputs in,
			@NonNull Map<String, GlUniform> uniforms) {
		var out = new LocatedOutput();
		var sources = new UniformSources(UniformSourcesTest.HOST);
		new ActiveBuiltins(UniformTable.BUILTINS, uniforms, sources, out).write(in);
		return out;
	}
}
