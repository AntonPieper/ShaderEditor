package de.markusfisch.android.shadereditor.engine.scene;

import androidx.annotation.NonNull;

import org.jetbrains.annotations.Contract;

import java.util.List;

import de.markusfisch.android.shadereditor.engine.GpuObject;

public record Framebuffer(
		int width,
		int height,
		@NonNull List<ColorAttachment> colorAttachments
) implements GpuObject {
	private static final Framebuffer DEFAULT = new Framebuffer(0, 0, List.of());

	@NonNull
	@Contract(" -> new")
	public static Framebuffer defaultFramebuffer() {
		return DEFAULT;
	}

	public boolean isDefault() {
		return this == DEFAULT;
	}
}