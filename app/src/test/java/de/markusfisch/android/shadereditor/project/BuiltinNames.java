package de.markusfisch.android.shadereditor.project;

import de.markusfisch.android.shadereditor.opengl.UniformTable;

/**
 * The engine's builtin uniforms, which the app hands in when it prepares
 * a project.
 */
public final class BuiltinNames {
	public static final Builtins ALL = UniformTable.builtins();

	private BuiltinNames() {
	}
}
