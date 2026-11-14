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
	// Main thread only. The permissions whose dialog is up. Its answer
	// is kept under a key of the permission by the activity's registry
	// when the activity is made again, so the new one takes it.
	private static final HashSet<String> asking = new HashSet<>();

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
			if (asking.contains(permission)) {
				// Asked by the activity before this one, as before a
				// rotation: its answer comes here.
				listen(activity, permission, denied);
				return;
			}
			if (!requested.add(permission)) {
				denied.run();
				return;
			}
			asking.add(permission);
			listen(activity, permission, denied).launch(permission);
		});
		return true;
	}

	// Main thread only.
	@NonNull
	private static ActivityResultLauncher<String> listen(
			@NonNull ComponentActivity activity, @NonNull String permission,
			@NonNull Runnable denied) {
		var launcher = new AtomicReference<ActivityResultLauncher<String>>();
		Handler main = new Handler(Looper.getMainLooper());
		launcher.set(activity.getActivityResultRegistry().register(
				"engine_permission_" + permission,
				new ActivityResultContracts.RequestPermission(),
				granted -> {
					// A result kept for a screen made again is delivered
					// within register, before launcher is set.
					main.post(() -> launcher.get().unregister());
					asking.remove(permission);
					if (!granted) {
						denied.run();
					}
				}));
		return launcher.get();
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
