package de.markusfisch.android.shadereditor.platform.render.gl.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.engine.Viewport;

public final class GlesRenderContext {
	/**
	 * Small stable surface for program lookups from handlers.
	 */
	public interface GlesProgramHandle {
		int programId();

		int locate(@NonNull String name);
	}
	@NonNull
	private final Viewport viewport;
	@Nullable
	private GlesProgramHandle currentProgram;

	public GlesRenderContext(@NonNull Viewport viewport) {
		this.viewport = viewport;
	}

	public void setCurrentProgram(@NonNull GlesProgramHandle p) {
		this.currentProgram = p;
	}

	@Nullable
	public GlesProgramHandle currentProgram() {
		return currentProgram;
	}

	@NonNull
	public Viewport getViewport() {
		return viewport;
	}
}