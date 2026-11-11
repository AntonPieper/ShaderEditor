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

import de.markusfisch.android.shadereditor.opengl.EngineHost;

final class ContextEngineHost implements EngineHost {
	private static final int PERMISSION_REQUEST_CODE = 1;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
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
	public void onPermissionNeeded(@NonNull String permission) {
		mainHandler.post(() -> {
			if (ContextCompat.checkSelfPermission(context, permission) ==
					PackageManager.PERMISSION_GRANTED) {
				return;
			}
			if (context instanceof Activity activity) {
				ActivityCompat.requestPermissions(
						activity,
						new String[]{permission},
						PERMISSION_REQUEST_CODE);
			}
		});
	}

	@Nullable
	@Override
	public LifecycleOwner cameraOwner() {
		return context instanceof LifecycleOwner owner ? owner : null;
	}
}
