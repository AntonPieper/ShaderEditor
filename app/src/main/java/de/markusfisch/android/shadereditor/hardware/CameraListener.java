package de.markusfisch.android.shadereditor.hardware;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;

public class CameraListener {
	private static final CameraArbiter ARBITER = new CameraArbiter();
	private static final long PERMISSION_POLL_MS = 500;

	public final int facing;

	private final int cameraTextureId;
	private final int deviceRotation;
	@NonNull
	private final Context context;
	@NonNull
	private final Size frameSize;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final CameraArbiter.Client client = new CameraArbiter.Client() {
		@Override
		public void acquire() {
			CameraListener.this.acquire();
		}

		@Override
		public void release() {
			CameraListener.this.release();
		}
	};
	// No callback reaches the engine when the permission is granted, in the
	// dialog, in the settings or with adb, so a listener without it checks
	// again until it is granted or the listener stops.
	private final Runnable waitForPermission = new Runnable() {
		@Override
		public void run() {
			if (hasPermission()) {
				ARBITER.request(client);
			} else {
				mainHandler.postDelayed(this, PERMISSION_POLL_MS);
			}
		}
	};

	// Main thread only.
	@Nullable
	private LifecycleOwner lifecycleOwner;
	@Nullable
	private ProcessCameraProvider cameraProvider;
	@Nullable
	private Preview preview;
	private boolean held;

	// Guarded by this: set on the main thread when the camera is bound,
	// read on the GL thread.
	private final float[] orientation = new float[4];
	private final float[] addent = new float[2];
	private boolean bound;

	// Every use of a SurfaceTexture holds surfaceLock. The main thread
	// creates one for each surface request and releases it when CameraX is
	// done with its surface, so the camera never draws into a released one.
	// The GL thread only updates the newest, and stops when the listener does.
	private final Object surfaceLock = new Object();
	@Nullable
	private SurfaceTexture surfaceTexture;
	private boolean stopped;

	public CameraListener(
			int cameraTextureId,
			int facing,
			int width,
			int height,
			int deviceRotation,
			@NonNull Context context) {
		this.cameraTextureId = cameraTextureId;
		this.facing = facing;
		this.context = context;
		this.frameSize = new Size(width, height);
		this.deviceRotation = deviceRotation;
	}

	public void register(
			@NonNull LifecycleOwner lifecycleOwner,
			@NonNull Runnable requestPermission) {
		mainHandler.post(() -> {
			this.lifecycleOwner = lifecycleOwner;
			if (hasPermission()) {
				ARBITER.request(client);
				return;
			}
			requestPermission.run();
			mainHandler.postDelayed(waitForPermission, PERMISSION_POLL_MS);
		});
	}

	public void unregister() {
		mainHandler.post(() -> {
			mainHandler.removeCallbacks(waitForPermission);
			ARBITER.withdraw(client);
			synchronized (surfaceLock) {
				stopped = true;
				surfaceTexture = null;
			}
		});
	}

	private void acquire() {
		held = true;
		if (cameraProvider != null) {
			bind(cameraProvider);
			return;
		}
		ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
				ProcessCameraProvider.getInstance(context);
		cameraProviderFuture.addListener(() -> {
			try {
				cameraProvider = cameraProviderFuture.get();
			} catch (ExecutionException | InterruptedException e) {
				return;
			}
			// A stop or a newer renderer may have taken the camera meanwhile.
			if (held) {
				bind(cameraProvider);
			}
		}, ContextCompat.getMainExecutor(context));
	}

	private void bind(@NonNull ProcessCameraProvider cameraProvider) {
		LifecycleOwner owner = lifecycleOwner;
		if (preview != null || owner == null ||
				owner.getLifecycle().getCurrentState() == Lifecycle.State.DESTROYED) {
			return;
		}
		Preview preview = new Preview.Builder()
				.setTargetResolution(frameSize)
				.build();
		preview.setSurfaceProvider(this::provideSurface);
		CameraSelector cameraSelector = new CameraSelector.Builder()
				.requireLensFacing(facing)
				.build();
		Camera camera;
		try {
			camera = cameraProvider.bindToLifecycle(owner, cameraSelector, preview);
		} catch (IllegalArgumentException | IllegalStateException e) {
			// No such lens, or the owner cannot take the use case.
			return;
		}
		this.preview = preview;
		synchronized (this) {
			CameraOrientation.compute(
					camera.getCameraInfo().getSensorRotationDegrees(),
					facing == CameraSelector.LENS_FACING_FRONT,
					deviceRotation,
					orientation,
					addent);
			bound = true;
		}
	}

	private void release() {
		held = false;
		synchronized (this) {
			bound = false;
		}
		if (preview != null && cameraProvider != null) {
			cameraProvider.unbind(preview);
			// CameraX keeps the last use case of a camera until its next
			// session; without its provider it does not keep this listener.
			preview.setSurfaceProvider(null);
		}
		preview = null;
	}

	private void provideSurface(@NonNull SurfaceRequest surfaceRequest) {
		SurfaceTexture texture;
		Surface surface;
		synchronized (surfaceLock) {
			if (stopped) {
				surfaceRequest.willNotProvideSurface();
				return;
			}
			texture = new SurfaceTexture(cameraTextureId);
			Size size = surfaceRequest.getResolution();
			texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
			surface = new Surface(texture);
			surfaceTexture = texture;
		}
		surfaceRequest.provideSurface(surface,
				ContextCompat.getMainExecutor(context),
				result -> {
					surface.release();
					synchronized (surfaceLock) {
						if (surfaceTexture == texture) {
							surfaceTexture = null;
						}
						texture.release();
					}
				});
	}

	private boolean hasPermission() {
		return ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
				PackageManager.PERMISSION_GRANTED;
	}

	/**
	 * Copies cameraOrientation and cameraAddent for the bound camera and
	 * returns true, or returns false while this listener waits for the
	 * permission or for another renderer to give the camera back.
	 */
	public synchronized boolean getOrientation(
			@NonNull float[] matrix,
			@NonNull float[] addent) {
		if (!bound) {
			return false;
		}
		System.arraycopy(orientation, 0, matrix, 0, orientation.length);
		System.arraycopy(this.addent, 0, addent, 0, this.addent.length);
		return true;
	}

	public void update() {
		synchronized (surfaceLock) {
			if (surfaceTexture != null) {
				surfaceTexture.updateTexImage();
			}
		}
	}
}