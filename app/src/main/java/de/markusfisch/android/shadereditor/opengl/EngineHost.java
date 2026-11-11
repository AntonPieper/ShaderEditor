package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;

import androidx.annotation.NonNull;

public interface EngineHost {
	@NonNull
	Context context();

	void onPermissionNeeded(@NonNull String permission);
}
