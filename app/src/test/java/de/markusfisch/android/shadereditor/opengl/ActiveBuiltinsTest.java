package de.markusfisch.android.shadereditor.opengl;

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
		new ActiveBuiltins(UniformTable.BUILTINS, uniforms, sources, out).write(in, 1L);
		return out;
	}
}
