package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.Contract;

import java.util.List;

import de.markusfisch.android.shadereditor.engine.scene.Framebuffer;

public record Pass(
		@NonNull Framebuffer target,
		@Nullable ClearColor clearColor,
		@Nullable ViewportRect viewport,
		@NonNull List<DrawCall> draws
) {
	@NonNull
	@Contract("_, _ -> new")
	public static Pass single(@NonNull Framebuffer target, @NonNull DrawCall draw) {
		return new Pass(target, null, null, List.of(draw));
	}
}