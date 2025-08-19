package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jetbrains.annotations.Contract;

import java.util.List;

import de.markusfisch.android.shadereditor.engine.scene.RenderTarget;

public record Pass(
		@NonNull RenderTarget target,
		@Nullable ClearColor clearColor,
		@Nullable ViewportRect viewport,
		@NonNull List<DrawCall> draws
) {
	@NonNull
	@Contract("_, _ -> new")
	public static Pass single(@NonNull RenderTarget target, @NonNull DrawCall draw) {
		return new Pass(target, null, null, List.of(draw));
	}
}