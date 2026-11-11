package de.markusfisch.android.shadereditor.hardware;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.core.SurfaceRequest;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.FloatBuffer;
import java.util.concurrent.ExecutionException;

public class CameraListener {
	private static final CameraArbiter ARBITER = new CameraArbiter();

	public final int facing;
	public final float[] addent = new float[]{0, 0};

	private final int cameraTextureId;

	@NonNull
	private final FloatBuffer orientationMatrix = FloatBuffer.allocate(4);

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

	// Main thread only.
	@Nullable
	private LifecycleOwner lifecycleOwner;
	@Nullable
	private ProcessCameraProvider cameraProvider;
	@Nullable
	private Preview preview;
	private boolean held;

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
		setOrientationAndFlip(deviceRotation);
	}

	@NonNull
	public FloatBuffer getOrientationMatrix() {
		return orientationMatrix;
	}

	public void register(@NonNull LifecycleOwner lifecycleOwner) {
		mainHandler.post(() -> {
			this.lifecycleOwner = lifecycleOwner;
			ARBITER.request(client);
		});
	}

	public void unregister() {
		mainHandler.post(() -> {
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
		try {
			cameraProvider.bindToLifecycle(owner, cameraSelector, preview);
		} catch (IllegalArgumentException | IllegalStateException e) {
			// No such lens, or the owner cannot take the use case.
			return;
		}
		this.preview = preview;
	}

	private void release() {
		held = false;
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

	public void update() {
		synchronized (surfaceLock) {
			if (surfaceTexture != null) {
				surfaceTexture.updateTexImage();
			}
		}
	}

	private void setOrientationAndFlip(int deviceRotation) {
		switch (deviceRotation) {
			default:
			case Surface.ROTATION_0:
				orientationMatrix.put(new float[]{
						0f, -1f,
						-1f, 0f,
				});
				addent[0] = 1f;
				addent[1] = 1f;
				break;
			case Surface.ROTATION_90:
				orientationMatrix.put(new float[]{
						1f, 0f,
						0f, -1f,
				});
				addent[0] = 0f;
				addent[1] = 1f;
				break;
			case Surface.ROTATION_180:
				orientationMatrix.put(new float[]{
						0f, 1f,
						1f, 0f,
				});
				addent[0] = 0f;
				addent[1] = 0f;
				break;
			case Surface.ROTATION_270:
				orientationMatrix.put(new float[]{
						-1f, 0f,
						0f, 1f,
				});
				addent[0] = 1f;
				addent[1] = 0f;
				break;
		}
		orientationMatrix.rewind();
	}
}