package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.project.ProjectPath;

/**
 * A small picture of a pass as the screen would show it, values cut to
 * 0..1: its longer side 96 pixels, in the pass's own proportions.
 *
 * @param file   the pass's file
 * @param width  its width in pixels
 * @param height its height in pixels
 * @param argb   colour ints, the top row first, with alpha as the pass
 *               drew it; filled again by the next round of pictures, so
 *               a host copies what it keeps
 */
public record PassPicture(
		@NonNull ProjectPath file,
		int width,
		int height,
		@NonNull int[] argb) {
}
