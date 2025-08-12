package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;
import java.util.List;
import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;

public record Pass(
		@NonNull Framebuffer target,
		float[] clearColorOrNull,      // e.g., new float[]{r,g,b,a} or null (no clear)
		int[] viewportXYWHOrNull,      // e.g., new int[]{x,y,w,h} or null (use framebuffer size)
		@NonNull List<DrawCall> draws
) { }