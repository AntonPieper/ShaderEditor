package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * A pass shown mapped from one range of values, the same for red,
 * green and blue so that colours keep their relations, to 0..1, alpha
 * shown as 1. A range across 0 is widened to be even around it, so 0 is
 * grey; any other range runs from black at low to white at high.
 *
 * @param file the pass's file
 * @param low  the value shown black
 * @param high the value shown white; more than low
 */
public record Fit(@NonNull ProjectPath file, float low, float high) {
	public Fit {
		if (!(high > low) || Float.isInfinite(low) || Float.isInfinite(high)) {
			throw new IllegalArgumentException(
					"a fit needs a finite range: " + low + " to " + high);
		}
	}

	// What a value is multiplied by, and what is added then.
	float scale() {
		return across() ? .5f / Math.max(-low, high) : 1f / (high - low);
	}

	float offset() {
		return across() ? .5f : -low / (high - low);
	}

	private boolean across() {
		return low < 0 && high > 0;
	}
}
