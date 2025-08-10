package de.markusfisch.android.shadereditor.platform.provider;

import android.util.Log;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.data.DataProvider;

public class TimeProvider implements DataProvider<Float> {
	boolean started = false;
	private long startTime;

	@Override
	public void start() {
		started = true;
		Log.d("TimeProvider", "start()");
		// For a simple timer, we can start it immediately.
		if (startTime == 0) {
			startTime = System.currentTimeMillis();
		}
	}

	@Override
	public void stop() {
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