package de.markusfisch.android.shadereditor.renderengine.provider;

import android.content.Context;
import android.hardware.SensorManager;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import java.util.Set;

import de.markusfisch.android.shadereditor.renderengine.DataProvider;
import de.markusfisch.android.shadereditor.renderengine.FrameContext;
import de.markusfisch.android.shadereditor.renderengine.ProviderKey;
import de.markusfisch.android.shadereditor.renderengine.UniformTypes;

public class ViewTransformProvider implements DataProvider {
	public static final ProviderKey<float[]> KEY_FINAL_ROTATION_MATRIX =
			new ProviderKey<>("view.finalRotationMatrix", float[].class, UniformTypes.MAT3);

	@NonNull
	@Override
	public Set<ProviderKey<?>> getProvidedKeys() {
		return Set.of(KEY_FINAL_ROTATION_MATRIX);
	}

	@NonNull @Override
	public Set<ProviderKey<?>> getDependencies() {
		return Set.of(SensorDataProvider.KEY_ROTATION_MATRIX);
	}

	@Override
	public void update(@NonNull FrameContext context) {
		float[] rawRotationMatrix = context.get(SensorDataProvider.KEY_ROTATION_MATRIX);
		if (rawRotationMatrix == null) {
			context.put(KEY_FINAL_ROTATION_MATRIX, new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1});
			return;
		}

		WindowManager windowManager = (WindowManager) context.getAndroidContext().getSystemService(Context.WINDOW_SERVICE);
		Display display = windowManager.getDefaultDisplay();
		int screenRotation = display.getRotation();

		// Simplified logic for axis remapping
		int axisX = SensorManager.AXIS_X;
		int axisY = SensorManager.AXIS_Y;
		// ... logic to set axisX/Y based on screenRotation ...

		float[] remappedMatrix = new float[9];
		SensorManager.remapCoordinateSystem(rawRotationMatrix, axisX, axisY, remappedMatrix);
		context.put(KEY_FINAL_ROTATION_MATRIX, remappedMatrix);
	}
}