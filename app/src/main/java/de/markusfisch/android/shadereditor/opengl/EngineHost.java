package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

public interface EngineHost {
	@NonNull
	Context context();

	void onPermissionNeeded(@NonNull String permission);

	@Nullable
	LifecycleOwner cameraOwner();
}
