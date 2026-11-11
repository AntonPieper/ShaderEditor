package de.markusfisch.android.shadereditor.adapter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.R;
import de.markusfisch.android.shadereditor.opengl.UniformTable;

public class PresetUniformAdapterTest {
	private static final List<PresetUniformAdapter.Uniform> UNIFORMS =
			PresetUniformAdapter.createUniforms(id -> "#" + id);

	@Test
	public void listsEveryBuiltinSortedByName() {
		var expected = new ArrayList<String>();
		for (var uniform : UniformTable.BUILTINS) {
			expected.add(uniform.getDeclarator());
		}
		expected.sort(String::compareTo);
		var names = new ArrayList<String>();
		for (var uniform : UNIFORMS) {
			names.add(uniform.name);
		}
		assertEquals(expected, names);
		assertEquals(List.of(
				"backbuffer",
				"battery",
				"cameraAddent",
				"cameraBack",
				"cameraFront",
				"cameraOrientation",
				"date",
				"daytime",
				"frame",
				"ftime",
				"gravity",
				"gyroscope",
				"inclination",
				"inclinationMatrix",
				"lastNotificationTime",
				"light",
				"linear",
				"magnetic",
				"mediaVolume",
				"micAmplitude",
				"mouse",
				"nightMode",
				"notificationCount",
				"offset",
				"orientation",
				"pointerCount",
				"pointers[10]",
				"powerConnected",
				"pressure",
				"proximity",
				"resolution",
				"rotationMatrix",
				"rotationVector",
				"second",
				"startRandom",
				"subsecond",
				"time",
				"touch",
				"touchStart"), names);
	}

	@Test
	public void entriesCarryTypeAndDescription() {
		assertUniform("backbuffer", "sampler2D", R.string.previous_frame);
		assertUniform("cameraBack", "samplerExternalOES", R.string.camera_back);
		assertUniform("mouse", "vec2", R.string.normalized_touch_position);
		assertUniform("pointers[10]", "vec3", R.string.positions_of_touches);
		assertUniform("rotationMatrix", "mat3", R.string.device_rotation_matrix);
		assertUniform("time", "float", R.string.time_in_seconds_since_load);
	}

	@Test
	public void onlySamplersAreSamplers() {
		assertTrue(find("backbuffer").isSampler());
		assertTrue(find("cameraFront").isSampler());
		assertFalse(find("cameraOrientation").isSampler());
	}

	private static void assertUniform(String name, String type, int description) {
		var uniform = find(name);
		assertEquals(type, uniform.type);
		assertEquals("#" + description, uniform.rationale);
	}

	private static PresetUniformAdapter.Uniform find(String name) {
		for (var uniform : UNIFORMS) {
			if (uniform.name.equals(name)) {
				return uniform;
			}
		}
		throw new AssertionError("No uniform " + name);
	}
}
