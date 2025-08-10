package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.Collections;
import java.util.List;

import de.markusfisch.android.shadereditor.engine.GpuObject;

/**
 * A data-driven description of a framebuffer.
 * It specifies attachments but does not manage any GPU resources itself.
 */
public record Framebuffer(
		int width,
		int height,
		@NonNull List<Texture> colorAttachments
) implements GpuObject {
	private static final Framebuffer DEFAULT_FRAMEBUFFER = new Framebuffer(0, 0,
			Collections.emptyList());

	// Special instance for the default (on-screen) framebuffer
	@NonNull
	@Contract(" -> new")
	public static Framebuffer defaultFramebuffer() {
		return DEFAULT_FRAMEBUFFER;
	}

	public boolean isDefault() {
		return this == DEFAULT_FRAMEBUFFER;
	}
}