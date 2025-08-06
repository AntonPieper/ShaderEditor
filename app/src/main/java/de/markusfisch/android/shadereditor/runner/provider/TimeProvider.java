package de.markusfisch.android.shadereditor.runner.provider;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataKey;
import de.markusfisch.android.shadereditor.engine.data.DataProvider;

public class TimeProvider implements DataProvider<Float> {
	public static final DataKey<Float> TIME = DataKey.create("engine.time", Float.class);

	private long startTime;
	boolean started = false;

	@NonNull
	@Override
	public DataKey<Float> getKey() {
		return TIME;
	}

	@Override
	public void start(@NonNull Context context) {
		started = true;
		Log.d("TimeProvider", "start()");
		// For a simple timer, we can start it immediately.
		if (startTime == 0) {
			startTime = System.currentTimeMillis();
		}
	}

	@Override
	public void stop(@NonNull Context context) {
		// No resources to release.
	}

	@NonNull
	@Override
	public Float getValue() {
		if (startTime == 0) {
			return 0.0f;
		}
		return (System.currentTimeMillis() - startTime) / 1000.0f;
	}
}