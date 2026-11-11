package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.project.RenderPlan.Sampling;

record DiscoveredSampler(
		@NonNull String name,
		int target,
		@NonNull Sampling sampling) {
}
