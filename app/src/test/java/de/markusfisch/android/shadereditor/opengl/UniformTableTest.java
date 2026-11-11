package de.markusfisch.android.shadereditor.opengl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class UniformTableTest {
	private static final float EPSILON = 1e-6f;

	static class RecordingOutput implements UniformOutput {
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
		public void setVec3(float x, float y, float z) {
			record("vec3", new float[]{x, y, z});
		}

		@Override
		public void setVec4(float x, float y, float z, float w) {
			record("vec4", new float[]{x, y, z, w});
		}

		@Override
		public void setVec3Array(@NonNull float[] values, int count) {
			float[] copy = new float[count * 3];
			System.arraycopy(values, 0, copy, 0, copy.length);
			record("vec3[" + count + "]", copy);
		}

		@Override
		public void setMat2(@NonNull float[] values) {
			record("mat2", values.clone());
		}

		@Override
		public void setMat3(@NonNull float[] values) {
			record("mat3", values.clone());
		}

		void record(String call, float[] value) {
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
	public void writesSystemValuesFromSources() {
		var nightMode = new SystemSources.NightMode();
		nightMode.night = true;
		assertWritten("nightMode", nightMode, "int", 1f);

		var notifications = new SystemSources.Notifications();
		notifications.count = 3;
		notifications.secondsSinceLast = 12.5f;
		assertWritten("notificationCount", notifications, "int", 3f);
		assertWritten("lastNotificationTime", notifications, "float", 12.5f);

		var battery = new SystemSources.Battery();
		battery.level = 0.75f;
		assertWritten("battery", battery, "float", 0.75f);

		var power = new SystemSources.PowerConnected();
		power.connected = true;
		assertWritten("powerConnected", power, "int", 1f);
		power.connected = false;
		assertWritten("powerConnected", power, "int", 0f);

		var clock = new SystemSources.Clock();
		clock.year = 2026f;
		clock.month = 10f;
		clock.day = 11f;
		clock.hour = 13f;
		clock.minute = 14f;
		clock.second = 15f;
		clock.secondOfDay = 47655f;
		assertWritten("date", clock, "vec4", 2026f, 10f, 11f, 47655f);
		assertWritten("daytime", clock, "vec3", 13f, 14f, 15f);

		var volume = new SystemSources.MediaVolume();
		volume.level = 0.5f;
		assertWritten("mediaVolume", volume, "float", 0.5f);

		var mic = new SystemSources.Microphone();
		mic.recording = true;
		mic.amplitude = 0.25f;
		assertWritten("micAmplitude", mic, "float", 0.25f);
	}

	@Test
	public void lastNotificationTimeIsNaNWithoutNotifications() {
		var notifications = new SystemSources.Notifications();
		RecordingOutput out = new RecordingOutput();
		UniformTable.get("lastNotificationTime").write(frame(), notifications, out);
		assertEquals(List.of("float"), out.calls);
		assertTrue(Float.isNaN(out.values.get(0)[0]));
	}

	@Test
	public void micAmplitudeIsNotWrittenWithoutRecording() {
		var mic = new SystemSources.Microphone();
		mic.amplitude = 0.25f;
		RecordingOutput out = new RecordingOutput();
		UniformTable.get("micAmplitude").write(frame(), mic, out);
		assertTrue(out.calls.isEmpty());
	}

	@Test
	public void describesDeclarations() {
		assertEquals(39, UniformTable.BUILTINS.size());
		assertEquals("vec4", UniformTable.get("date").getType().getKeyword());
		assertEquals(UniformTable.get("date").getSource(),
				UniformTable.get("daytime").getSource());
		UniformDef pointers = UniformTable.get("pointers");
		assertEquals(GlslType.VEC3, pointers.getType());
		assertEquals("pointers[10]", pointers.getDeclarator());
		assertEquals("time", UniformTable.get("time").getDeclarator());
		assertEquals("int", UniformTable.get("frame").getType().getKeyword());
	}

	@Test
	public void writesCameraValuesWhileOpen() {
		var camera = new CameraSource();
		camera.open = true;
		System.arraycopy(new float[]{0f, -1f, -1f, 0f}, 0, camera.orientation, 0, 4);
		camera.addent[0] = 1f;
		camera.addent[1] = 0.5f;
		assertWritten("cameraOrientation", camera, "mat2", 0f, -1f, -1f, 0f);
		assertWritten("cameraAddent", camera, "vec2", 1f, 0.5f);
	}

	@Test
	public void cameraValuesAreNotWrittenWhileClosed() {
		var camera = new CameraSource();
		camera.addent[0] = 1f;
		for (String name : List.of("cameraOrientation", "cameraAddent")) {
			RecordingOutput out = new RecordingOutput();
			UniformTable.get(name).write(frame(), camera, out);
			assertTrue(out.calls.isEmpty());
		}
	}

	@Test
	public void samplersAreDeclarationsOnly() {
		assertEquals(GlslType.SAMPLER_2D, UniformTable.get("backbuffer").getType());
		assertEquals("samplerExternalOES",
				UniformTable.get("cameraBack").getType().getKeyword());
		assertEquals(UniformTable.get("cameraBack").getSource(),
				UniformTable.get("cameraOrientation").getSource());
		for (String name : List.of("backbuffer", "cameraBack", "cameraFront")) {
			UniformDef sampler = UniformTable.get(name);
			assertTrue(sampler.isDeclarationOnly());
			assertThrows(IllegalStateException.class,
					() -> sampler.write(frame(), new CameraSource(), new RecordingOutput()));
		}
		assertFalse(UniformTable.get("cameraAddent").isDeclarationOnly());
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
		UniformTable.get(name).write(in, null, out);
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

	private static void assertWritten(
			String name,
			UniformSource source,
			String call,
			float... expected) {
		RecordingOutput out = new RecordingOutput();
		UniformTable.get(name).write(frame(), source, out);
		assertEquals(List.of(call), out.calls);
		assertArrayEquals(expected, out.values.get(0), EPSILON);
	}
}
