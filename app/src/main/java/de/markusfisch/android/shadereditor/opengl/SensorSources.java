package de.markusfisch.android.shadereditor.opengl;

import android.hardware.SensorManager;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import de.markusfisch.android.shadereditor.hardware.AccelerometerListener;
import de.markusfisch.android.shadereditor.hardware.GravityListener;
import de.markusfisch.android.shadereditor.hardware.GyroscopeListener;
import de.markusfisch.android.shadereditor.hardware.LightListener;
import de.markusfisch.android.shadereditor.hardware.LinearAccelerationListener;
import de.markusfisch.android.shadereditor.hardware.MagneticFieldListener;
import de.markusfisch.android.shadereditor.hardware.PressureListener;
import de.markusfisch.android.shadereditor.hardware.ProximityListener;
import de.markusfisch.android.shadereditor.hardware.RotationVectorListener;

final class SensorSources {
	// Serves both the gravity and the linear acceleration fallback.
	static final class Accelerometer implements UniformSource {
		static final Key<Accelerometer> KEY = Accelerometer::new;

		@Nullable
		float[] gravity;
		@Nullable
		float[] linear;
		private AccelerometerListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new AccelerometerListener(sources.sensors());
			if (listener.register()) {
				gravity = listener.gravity;
				linear = listener.linear;
			}
		}

		@Override
		public void stop() {
			listener.unregister();
			gravity = null;
			linear = null;
		}
	}

	static final class Gravity implements UniformSource {
		static final Key<Gravity> KEY = Gravity::new;

		@Nullable
		float[] values;
		private GravityListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new GravityListener(sources.sensors());
			values = listener.register()
					? listener.values
					: sources.start(Accelerometer.KEY).gravity;
		}

		@Override
		public void stop() {
			listener.unregister();
			values = null;
		}
	}

	static final class Linear implements UniformSource {
		static final Key<Linear> KEY = Linear::new;

		@Nullable
		float[] values;
		private LinearAccelerationListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new LinearAccelerationListener(sources.sensors());
			values = listener.register()
					? listener.values
					: sources.start(Accelerometer.KEY).linear;
		}

		@Override
		public void stop() {
			listener.unregister();
			values = null;
		}
	}

	static final class Gyroscope implements UniformSource {
		static final Key<Gyroscope> KEY = Gyroscope::new;

		@Nullable
		float[] values;
		private GyroscopeListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new GyroscopeListener(sources.sensors());
			values = listener.register() ? listener.rotation : null;
		}

		@Override
		public void stop() {
			listener.unregister();
			values = null;
		}
	}

	static final class Magnetic implements UniformSource {
		static final Key<Magnetic> KEY = Magnetic::new;

		@Nullable
		float[] values;
		@Nullable
		float[] filtered;
		private MagneticFieldListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new MagneticFieldListener(sources.sensors());
			if (listener.register()) {
				values = listener.values;
				filtered = listener.filtered;
			}
		}

		@Override
		public void stop() {
			listener.unregister();
			values = null;
			filtered = null;
		}
	}

	static final class Light implements UniformSource {
		static final Key<Light> KEY = Light::new;

		boolean registered;
		float ambient;
		private LightListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new LightListener(sources.sensors());
			registered = listener.register();
		}

		@Override
		public void stop() {
			listener.unregister();
			registered = false;
		}

		@Override
		public void update(long now) {
			if (registered) {
				ambient = listener.getAmbient();
			}
		}
	}

	static final class Pressure implements UniformSource {
		static final Key<Pressure> KEY = Pressure::new;

		boolean registered;
		float pressure;
		private PressureListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new PressureListener(sources.sensors());
			registered = listener.register();
		}

		@Override
		public void stop() {
			listener.unregister();
			registered = false;
		}

		@Override
		public void update(long now) {
			if (registered) {
				pressure = listener.getPressure();
			}
		}
	}

	static final class Proximity implements UniformSource {
		static final Key<Proximity> KEY = Proximity::new;

		boolean registered;
		float centimeters;
		private ProximityListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new ProximityListener(sources.sensors());
			registered = listener.register();
		}

		@Override
		public void stop() {
			listener.unregister();
			registered = false;
		}

		@Override
		public void update(long now) {
			if (registered) {
				centimeters = listener.getCentimeters();
			}
		}
	}

	static final class RotationVector implements UniformSource {
		static final Key<RotationVector> KEY = RotationVector::new;

		@Nullable
		float[] values;
		private RotationVectorListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new RotationVectorListener(sources.sensors());
			values = listener.register() ? listener.values : null;
		}

		@Override
		public void stop() {
			listener.unregister();
			values = null;
		}
	}

	static final class Rotation implements UniformSource {
		static final Key<Rotation> KEY = Rotation::new;

		final float[] matrix = new float[9];
		final float[] inclinationMatrix = new float[9];
		final float[] orientation = new float[]{0, 0, 0};
		private final float[] columns = new float[9];
		private final float[] remapped = new float[9];
		boolean available;
		boolean inclined;
		Gravity gravity;
		Magnetic magnetic;
		@Nullable
		RotationVector vector;
		private UniformSources sources;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			this.sources = sources;
			gravity = sources.start(Gravity.KEY);
			magnetic = sources.start(Magnetic.KEY);
			if (gravity.values == null || magnetic.filtered == null) {
				vector = sources.start(RotationVector.KEY);
			}
		}

		@Override
		public void stop() {
		}

		@Override
		public void update(long now) {
			inclined = gravity.values != null &&
					magnetic.filtered != null &&
					SensorManager.getRotationMatrix(
							matrix,
							inclinationMatrix,
							gravity.values,
							magnetic.filtered);
			if (!inclined) {
				if (vector == null || vector.values == null) {
					available = false;
					return;
				}
				SensorManager.getRotationMatrixFromVector(matrix, vector.values);
			}
			available = true;

			int deviceRotation = sources.getDeviceRotation();
			if (deviceRotation != Surface.ROTATION_0) {
				record AxisPair(int x, int y) {
				}
				@SuppressWarnings("SuspiciousNameCombination")
				var rotation = switch (deviceRotation) {
					case Surface.ROTATION_90 ->
							new AxisPair(SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X);
					case Surface.ROTATION_180 ->
							new AxisPair(SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y);
					case Surface.ROTATION_270 ->
							new AxisPair(SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X);
					default -> new AxisPair(SensorManager.AXIS_X, SensorManager.AXIS_Y);
				};
				// In place, remapCoordinateSystem overwrites values it still reads.
				SensorManager.remapCoordinateSystem(
						matrix,
						rotation.x(),
						rotation.y(),
						remapped);
				System.arraycopy(remapped, 0, matrix, 0, 9);
			}
		}

		// SensorManager fills matrices row by row.
		@NonNull
		float[] columns(@NonNull float[] rows) {
			for (int row = 0; row < 3; ++row) {
				for (int column = 0; column < 3; ++column) {
					columns[column * 3 + row] = rows[row * 3 + column];
				}
			}
			return columns;
		}

		@NonNull
		float[] getOrientation() {
			return SensorManager.getOrientation(matrix, orientation);
		}

		float getInclination() {
			return SensorManager.getInclination(inclinationMatrix);
		}
	}

	private SensorSources() {
	}
}
