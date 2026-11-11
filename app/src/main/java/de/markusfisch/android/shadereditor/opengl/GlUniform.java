package de.markusfisch.android.shadereditor.opengl;

import androidx.annotation.NonNull;

record GlUniform(@NonNull String name, int type, int size, int location) {
}
