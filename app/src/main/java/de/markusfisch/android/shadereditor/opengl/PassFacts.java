package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import de.markusfisch.android.shadereditor.project.HostDirective;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * What can be told of a pass reliably and cheaply, as facts, never as a
 * verdict: a pass that is black on purpose reads like any other.
 * Values come from a sample, so one odd pixel can be missed.
 *
 * @param file      the pass's file
 * @param drawn     whether it draws; a first frame nothing reads does
 *                  not
 * @param problems  the build's errors and warnings in its file
 * @param width     its target's width in pixels, 0 when it draws none
 * @param height    its target's height in pixels, 0 when it draws none
 * @param format    its target's format, null when it draws none
 * @param sample    the side of the square sample the values below are
 *                  from: 96 of an 8-bit picture, 32 of a float target
 * @param same      when every sampled pixel holds one value: it, as
 *                  red, green, blue and alpha; else null
 * @param low       for a float target in OpenGL ES 3: the lowest finite
 *                  value of each channel; else null
 * @param high      the same for the highest
 * @param nonFinite for a float target in OpenGL ES 3: whether a NaN or
 *                  an infinity was sampled
 * @param unchanged how long the sample has stayed the same, in
 *                  nanoseconds, as far as facts were made meanwhile
 */
public record PassFacts(
		@NonNull ProjectPath file,
		boolean drawn,
		@NonNull List<ProjectDiagnostic> problems,
		int width,
		int height,
		@Nullable HostDirective.Format format,
		int sample,
		@Nullable float[] same,
		@Nullable float[] low,
		@Nullable float[] high,
		boolean nonFinite,
		long unchanged) {
	public PassFacts {
		problems = List.copyOf(problems);
	}
}
