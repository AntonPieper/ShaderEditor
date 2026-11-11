package de.markusfisch.android.shadereditor.opengl;

import android.Manifest;
import android.opengl.GLES11Ext;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;

import de.markusfisch.android.shadereditor.hardware.CameraListener;

final class CameraSource implements UniformSource {
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
		if (texture == null ||
				!texture.isValid() ||
				texture.getTarget() != GLES11Ext.GL_TEXTURE_EXTERNAL_OES) {
			return;
		}

		int lensFacing = UniformTable.CAMERA_BACK.equals(
				binding.sampler().name())
				? CameraSelector.LENS_FACING_BACK
				: CameraSelector.LENS_FACING_FRONT;
		host.onPermissionNeeded(Manifest.permission.CAMERA);
		listener = new CameraListener(
				texture.getId(),
				lensFacing,
				renderWidth,
				renderHeight,
				sources.getDeviceRotation(),
				host.context());
		listener.register(owner);

		var orientationMatrix = listener.getOrientationMatrix();
		orientationMatrix.rewind();
		orientationMatrix.get(orientation);
		orientationMatrix.rewind();
		System.arraycopy(listener.addent, 0, addent, 0, addent.length);
		open = true;
	}

	private void close() {
		open = false;
		if (listener != null) {
			listener.unregister();
			listener = null;
		}
	}
}
