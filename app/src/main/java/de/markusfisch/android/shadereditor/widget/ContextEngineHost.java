package de.markusfisch.android.shadereditor.widget;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import java.util.HashSet;

import de.markusfisch.android.shadereditor.opengl.EngineHost;
import de.markusfisch.android.shadereditor.service.NotificationService;

final class ContextEngineHost implements EngineHost {
	private static final int PERMISSION_REQUEST_CODE = 1;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	// Main thread only. Each permission is asked for once per view, so a
	// denial does not bring the dialog back every time the view resumes.
	private final HashSet<String> requested = new HashSet<>();
	@NonNull
	private final Context context;

	ContextEngineHost(@NonNull Context context) {
		this.context = context;
	}

	@NonNull
	@Override
	public Context context() {
		return context;
	}

	@Override
	public boolean onPermissionNeeded(@NonNull String permission) {
		if (isGranted(permission)) {
			return true;
		}
		if (!(context instanceof Activity activity)) {
			return false;
		}
		mainHandler.post(() -> {
			if (!isGranted(permission) &&
					requested.add(permission) &&
					!activity.isFinishing()) {
				ActivityCompat.requestPermissions(
						activity,
						new String[]{permission},
						PERMISSION_REQUEST_CODE);
			}
		});
		return true;
	}

	@Override
	public boolean onNotificationAccessNeeded() {
		if (!(context instanceof Activity)) {
			return false;
		}
		NotificationService.requirePermissions(context);
		return true;
	}

	@Nullable
	@Override
	public LifecycleOwner cameraOwner() {
		return context instanceof LifecycleOwner owner ? owner : null;
	}

	private boolean isGranted(@NonNull String permission) {
		return ContextCompat.checkSelfPermission(context, permission) ==
				PackageManager.PERMISSION_GRANTED;
	}
}
