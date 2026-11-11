package de.markusfisch.android.shadereditor.hardware;

import androidx.annotation.NonNull;

// A reading that one thread publishes and another copies out whole.
public final class Sample {
	private final float[] values;

	public Sample(int size) {
		values = new float[size];
	}

	public synchronized void publish(@NonNull float[] source) {
		System.arraycopy(source, 0, values, 0, values.length);
	}

	public synchronized void read(@NonNull float[] target) {
		System.arraycopy(values, 0, target, 0, values.length);
	}
}
