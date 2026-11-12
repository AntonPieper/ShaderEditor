package de.markusfisch.android.shadereditor.project;

import java.util.Map;

import de.markusfisch.android.shadereditor.opengl.UniformTable;

/**
 * The GLSL types of the engine's builtin uniforms by name, which the app
 * reserves when it prepares a project.
 */
public final class BuiltinNames {
	public static final Map<String, String> ALL =
			Map.copyOf(UniformTable.types());

	private BuiltinNames() {
	}
}
