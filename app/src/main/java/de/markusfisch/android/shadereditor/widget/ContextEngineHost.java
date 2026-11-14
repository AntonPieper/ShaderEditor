package de.markusfisch.android.shadereditor.widget;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import java.util.HashSet;
import java.util.concurrent.atomic.AtomicReference;

import de.markusfisch.android.shadereditor.opengl.EngineHost;
import de.markusfisch.android.shadereditor.service.NotificationService;

final class ContextEngineHost implements EngineHost {
	// Main thread only. Each permission is asked for once while the app
	// runs, so a denial does not bring the dialog back every time a
	// shader starts. After the app started again, it asks again if the
	// platform still lets it show the dialog.
	private static final HashSet<String> requested = new HashSet<>();
	private static int requests;

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
	public boolean onPermissionNeeded(@NonNull String permission,
			@NonNull Runnable denied) {
		if (isGranted(permission)) {
			return true;
		}
		if (!(context instanceof ComponentActivity activity)) {
			return false;
		}
		mainHandler.post(() -> {
			if (isGranted(permission) || activity.isFinishing()) {
				return;
			}
			if (!requested.add(permission)) {
				denied.run();
				return;
			}
			var launcher = new AtomicReference<ActivityResultLauncher<String>>();
			launcher.set(activity.getActivityResultRegistry().register(
					"engine_permission_" + ++requests,
					new ActivityResultContracts.RequestPermission(),
					granted -> {
						launcher.get().unregister();
						if (!granted) {
							denied.run();
						}
					}));
			launcher.get().launch(permission);
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
