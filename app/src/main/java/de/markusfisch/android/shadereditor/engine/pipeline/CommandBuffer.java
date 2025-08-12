package de.markusfisch.android.shadereditor.engine.pipeline;

import androidx.annotation.NonNull;

import java.util.List;

public record CommandBuffer(@NonNull List<GpuCommand> cmds) {
}