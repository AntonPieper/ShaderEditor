package de.markusfisch.android.shadereditor.runner.provider.sensor;

import android.content.Context;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataProvider;

public abstract class AbstractSensorProvider<T> implements DataProvider<T> {
	@Override
	public void start(@NonNull Context context) {
		RotationSensorManager.getInstance().start(context);
	}

	@Override
	public void stop(@NonNull Context context) {
		RotationSensorManager.getInstance().stop();
	}
}