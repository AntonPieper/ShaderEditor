package de.markusfisch.android.shadereditor.project;

import java.util.HashSet;
import java.util.Set;

import de.markusfisch.android.shadereditor.opengl.UniformDef;
import de.markusfisch.android.shadereditor.opengl.UniformTable;

/**
 * The names of the engine's builtin uniforms, which the app reserves
 * when it prepares a project.
 */
public final class BuiltinNames {
	public static final Set<String> ALL = names();

	private BuiltinNames() {
	}

	private static Set<String> names() {
		Set<String> names = new HashSet<>();
		for (UniformDef uniform : UniformTable.BUILTINS) {
			names.add(uniform.getName());
		}
		return Set.copyOf(names);
	}
}
