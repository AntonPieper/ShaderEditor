package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class UniformTableTest {
	private static final float EPSILON = 1e-6f;

	private static final class RecordingOutput implements UniformOutput {
		final List<String> calls = new ArrayList<>();
		final List<float[]> values = new ArrayList<>();

		@Override
		public void setInt(int value) {
			record("int", new float[]{value});
		}

		@Override
		public void setFloat(float value) {
			record("float", new float[]{value});
		}

		@Override
		public void setVec2(float x, float y) {
			record("vec2", new float[]{x, y});
		}

		@Override
		public void setVec3Array(@NonNull float[] values, int count) {
			float[] copy = new float[count * 3];
			System.arraycopy(values, 0, copy, 0, copy.length);
			record("vec3[" + count + "]", copy);
		}

		private void record(String call, float[] value) {
			calls.add(call);
			values.add(value);
		}
	}

	@Test
	public void writesTimeDerivedValues() {
		FrameInputs in = frame();
		assertWritten(in, "time", "float", 7.25f);
		assertWritten(in, "second", "int", 7f);
		assertWritten(in, "subsecond", "float", 0.25f);
		assertWritten(in, "frame", "int", 42f);
		// 7.25 % 3 = 1.25; 1.25 / 3 * 2 - 1
		assertWritten(in, "ftime", "float", 1.25f / 3f * 2f - 1f);
		assertWritten(in, "startRandom", "float", 0.5f);
	}

	@Test
	public void writesSurfaceAndPointerValues() {
		FrameInputs in = frame();
		assertWritten(in, "resolution", "vec2", 320f, 240f);
		assertWritten(in, "touch", "vec2", 10f, 20f);
		assertWritten(in, "touchStart", "vec2", 1f, 2f);
		assertWritten(in, "mouse", "vec2", 0.25f, 0.75f);
		assertWritten(in, "offset", "vec2", 0.5f, 0f);
		assertWritten(in, "pointerCount", "int", 2f);
		assertWritten(in, "pointers", "vec3[2]",
				10f, 20f, 3f, 30f, 40f, 4f);
	}

	@Test
	public void pointersAreNotWrittenWithoutTouches() {
		FrameInputs in = frame();
		in.pointerCount = 0;
		RecordingOutput out = write(in, "pointers");
		assertTrue(out.calls.isEmpty());
		assertWritten(in, "pointerCount", "int", 0f);
	}

	@Test
	public void ftimeWrapsAtCycleLength() {
		FrameInputs in = frame();
		in.time = 3f;
		assertWritten(in, "ftime", "float", -1f);
		in.time = 1.5f;
		assertWritten(in, "ftime", "float", 0f);
	}

	@Test
	public void describesDeclarations() {
		assertEquals(13, UniformTable.BUILTINS.size());
		UniformDef pointers = UniformTable.get("pointers");
		assertEquals(GlslType.VEC3, pointers.getType());
		assertEquals("pointers[10]", pointers.getDeclarator());
		assertEquals("time", UniformTable.get("time").getDeclarator());
		assertEquals("int", UniformTable.get("frame").getType().getKeyword());
	}

	@NonNull
	private static FrameInputs frame() {
		FrameInputs in = new FrameInputs();
		in.time = 7.25f;
		in.frame = 42;
		in.fTimeMax = 3f;
		in.width = 320f;
		in.height = 240f;
		in.touchX = 10f;
		in.touchY = 20f;
		in.touchStartX = 1f;
		in.touchStartY = 2f;
		in.mouseX = 0.25f;
		in.mouseY = 0.75f;
		in.pointerCount = 2;
		float[] pointers = {10f, 20f, 3f, 30f, 40f, 4f};
		System.arraycopy(pointers, 0, in.pointers, 0, pointers.length);
		in.offsetX = 0.5f;
		in.offsetY = 0f;
		in.startRandom = 0.5f;
		return in;
	}

	@NonNull
	private static RecordingOutput write(FrameInputs in, String name) {
		RecordingOutput out = new RecordingOutput();
		UniformTable.get(name).getWriter().write(in, out);
		return out;
	}

	private static void assertWritten(
			FrameInputs in,
			String name,
			String call,
			float... expected) {
		RecordingOutput out = write(in, name);
		assertEquals(List.of(call), out.calls);
		assertArrayEquals(expected, out.values.get(0), EPSILON);
	}
}
