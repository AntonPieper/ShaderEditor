package de.markusfisch.android.shadereditor.platform.render.gl.core;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class GlesRenderContext {
	/**
	 * Small stable surface for program lookups from handlers.
	 */
	public interface GlesProgramHandle {
		int programId();

		int locate(@NonNull String name);
	}

	@Nullable
	private GlesProgramHandle current;

	public void setCurrentProgram(@NonNull GlesProgramHandle p) {
		this.current = p;
	}

	public GlesProgramHandle currentProgram() {
		return current;
	}
}