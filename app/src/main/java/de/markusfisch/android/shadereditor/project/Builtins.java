package de.markusfisch.android.shadereditor.project;

import java.util.Map;
import java.util.Set;

import de.markusfisch.android.shadereditor.project.HostDirective.TextureKind;

/**
 * The uniforms an engine provides under fixed names, as far as
 * preparing a project asks about them: an input with a path cannot
 * take one of the names, and an input without a path must name one of
 * the samplers and declare it as the texture it is.
 *
 * @param names    the name of every builtin uniform
 * @param samplers how the samplers among them are read, by name
 */
public record Builtins(Set<String> names, Map<String, TextureKind> samplers) {
	public Builtins {
		names = Set.copyOf(names);
		samplers = Map.copyOf(samplers);
	}
}
