package de.markusfisch.android.shadereditor.opengl;

import android.Manifest;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;

import de.markusfisch.android.shadereditor.hardware.CameraListener;

final class CameraSource implements UniformSource {
	// The texture the camera streams into, for the camera sampler of
	// this name.
	record Binding(@NonNull String sampler, @NonNull GlExternalTexture texture) {
	}

	static final Key<CameraSource> KEY = CameraSource::new;

	final float[] orientation = new float[4];
	final float[] addent = new float[2];
	boolean open;
	private EngineHost host;
	private UniformSources sources;
	@Nullable
	private CameraListener listener;

	@Override
	public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
		this.host = host;
		this.sources = sources;
		if (host.cameraOwner() == null) {
			sources.warn("The camera is not available here");
		}
		reopen();
	}

	@Override
	public void stop() {
		close();
	}

	@Override
	public void update(long now) {
		if (listener != null) {
			listener.update();
			open = listener.getOrientation(orientation, addent);
			String failure = listener.takeFailure();
			if (failure != null) {
				sources.warn(failure);
			}
		}
	}

	void reopen() {
		close();

		var binding = sources.getCameraBinding();
		int renderWidth = sources.getRenderWidth();
		int renderHeight = sources.getRenderHeight();
		if (binding == null || renderWidth <= 0 || renderHeight <= 0) {
			return;
		}
		var owner = host.cameraOwner();
		if (owner == null) {
			return;
		}

		var texture = binding.texture();
		if (!texture.isValid()) {
			return;
		}

		int lensFacing = UniformTable.CAMERA_BACK.equals(binding.sampler())
				? CameraSelector.LENS_FACING_BACK
				: CameraSelector.LENS_FACING_FRONT;
		listener = new CameraListener(
				texture.getId(),
				lensFacing,
				renderWidth,
				renderHeight,
				sources.getDeviceRotation(),
				host.context());
		listener.register(owner,
				() -> host.onPermissionNeeded(Manifest.permission.CAMERA));
	}

	private void close() {
		open = false;
		if (listener != null) {
			listener.unregister();
			listener = null;
		}
	}
}
