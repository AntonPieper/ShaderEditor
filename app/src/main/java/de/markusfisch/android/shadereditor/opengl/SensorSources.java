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
	static final class Accelerometer implements UniformSource {
		static final Key<Accelerometer> KEY = Accelerometer::new;

		private AccelerometerListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new AccelerometerListener(host.context());
		}

		@Override
		public void stop() {
			listener.unregister();
		}

		// Registers on every call, so a second fallback finds the
		// listener already listening and gets nothing.
		@Nullable
		AccelerometerListener register() {
			return listener.register() ? listener : null;
		}
	}

	static final class Gravity implements UniformSource {
		static final Key<Gravity> KEY = Gravity::new;

		@Nullable
		float[] values;
		boolean fromSensor;
		private GravityListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new GravityListener(host.context());
			fromSensor = listener.register();
			if (fromSensor) {
				values = listener.values;
			} else {
				var accelerometer = sources.start(Accelerometer.KEY).register();
				values = accelerometer != null ? accelerometer.gravity : null;
			}
		}

		@Override
		public void stop() {
			listener.unregister();
			values = null;
			fromSensor = false;
		}
	}

	static final class Linear implements UniformSource {
		static final Key<Linear> KEY = Linear::new;

		@Nullable
		float[] values;
		private LinearAccelerationListener listener;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			listener = new LinearAccelerationListener(host.context());
			if (listener.register()) {
				values = listener.values;
			} else {
				var accelerometer = sources.start(Accelerometer.KEY).register();
				values = accelerometer != null ? accelerometer.linear : null;
			}
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
			listener = new GyroscopeListener(host.context());
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
			listener = new MagneticFieldListener(host.context());
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
			listener = new LightListener(host.context());
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
			listener = new PressureListener(host.context());
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
			listener = new ProximityListener(host.context());
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
			listener = new RotationVectorListener(host.context());
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
			// rotationVector precedes this source in the table, so it is
			// already running when the shader declares it.
			vector = magnetic.values == null
					? sources.start(RotationVector.KEY)
					: sources.get(RotationVector.KEY);
		}

		@Override
		public void stop() {
		}

		@Override
		public void update(long now) {
			inclined = gravity.fromSensor &&
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
				SensorManager.remapCoordinateSystem(
						matrix,
						rotation.x(),
						rotation.y(),
						matrix);
			}
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
