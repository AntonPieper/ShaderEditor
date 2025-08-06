package de.markusfisch.android.shadereditor.engine.model;

import androidx.annotation.NonNull;

public record Shader(@NonNull String vertexShader, @NonNull String fragmentShader) {
}
