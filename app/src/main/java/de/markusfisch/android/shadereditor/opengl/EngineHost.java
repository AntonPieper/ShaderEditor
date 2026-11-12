package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;

public interface EngineHost {
	@NonNull
	Context context();

	// Asks the user for a missing permission. Returns false when it is
	// missing and this host cannot ask, such as a service without UI.
	boolean onPermissionNeeded(@NonNull String permission);

	// Asks the user to allow notification access, or returns false when
	// this host cannot ask.
	boolean onNotificationAccessNeeded();

	@Nullable
	LifecycleOwner cameraOwner();
}
