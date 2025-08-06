package de.markusfisch.android.shadereditor.renderengine;

/**
 * A simple, self-contained command to apply a uniform.
 * The renderer just executes this without needing to know the details.
 */
@FunctionalInterface
public interface UniformCommand {
    void apply();
}