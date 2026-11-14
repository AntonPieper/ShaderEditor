package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.BatteryManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Calendar;

import de.markusfisch.android.shadereditor.app.ShaderEditorApp;
import de.markusfisch.android.shadereditor.hardware.MicInputListener;
import de.markusfisch.android.shadereditor.hardware.Retry;
import de.markusfisch.android.shadereditor.service.NotificationService;

final class SystemSources {
	private static final long BATTERY_UPDATE_INTERVAL = 10000000000L;
	private static final long DATE_UPDATE_INTERVAL = 1000000000L;
	private static final long MEDIA_VOLUME_UPDATE_INTERVAL = 1000000000L;

	static final class NightMode implements UniformSource {
		static final Key<NightMode> KEY = NightMode::new;

		boolean night;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			night = (host.context().getResources().getConfiguration().uiMode &
					Configuration.UI_MODE_NIGHT_MASK) ==
					Configuration.UI_MODE_NIGHT_YES;
		}

		@Override
		public void stop() {
		}
	}

	static final class Notifications implements UniformSource {
		static final Key<Notifications> KEY = Notifications::new;

		int count;
		float secondsSinceLast = Float.NaN;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			if (NotificationService.isPermissionRequired(host.context()) &&
					!host.onNotificationAccessNeeded()) {
				sources.warn("Notification access is off; run this shader " +
						"in the app once to turn it on");
			}
		}

		@Override
		public void stop() {
		}

		@Override
		public void update(long now) {
			count = NotificationService.getCount();
			Long lastTime = NotificationService.getLastNotificationTime();
			secondsSinceLast = lastTime == null
					? Float.NaN
					: (System.currentTimeMillis() - lastTime) / 1000f;
		}
	}

	static final class Battery implements UniformSource {
		static final Key<Battery> KEY = Battery::new;

		float level;
		private Context context;
		private long lastUpdate;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			context = host.context();
		}

		@Override
		public void stop() {
		}

		@Override
		public void update(long now) {
			if (now - lastUpdate > BATTERY_UPDATE_INTERVAL) {
				level = getBatteryLevel(context);
				lastUpdate = now;
			}
		}

		private static float getBatteryLevel(@NonNull Context context) {
			Intent batteryStatus = context.registerReceiver(
					null,
					new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
			if (batteryStatus == null) {
				return 0;
			}
			int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
			int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
			return (float) level / scale;
		}
	}

	static final class PowerConnected implements UniformSource {
		static final Key<PowerConnected> KEY = PowerConnected::new;

		boolean connected;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
		}

		@Override
		public void stop() {
		}

		@Override
		public void update(long now) {
			connected = ShaderEditorApp.preferences.isPowerConnected();
		}
	}

	static final class Clock implements UniformSource {
		static final Key<Clock> KEY = Clock::new;

		float year;
		float month;
		float day;
		float secondOfDay;
		float hour;
		float minute;
		float second;
		private long lastUpdate;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
		}

		@Override
		public void stop() {
		}

		@Override
		public void update(long now) {
			if (now - lastUpdate <= DATE_UPDATE_INTERVAL) {
				return;
			}
			Calendar calendar = Calendar.getInstance();
			year = calendar.get(Calendar.YEAR);
			month = calendar.get(Calendar.MONTH);
			day = calendar.get(Calendar.DAY_OF_MONTH);
			hour = calendar.get(Calendar.HOUR_OF_DAY);
			minute = calendar.get(Calendar.MINUTE);
			second = calendar.get(Calendar.SECOND);
			secondOfDay = hour * 3600f + minute * 60f + second;
			lastUpdate = now;
		}
	}

	static final class MediaVolume implements UniformSource {
		static final Key<MediaVolume> KEY = MediaVolume::new;

		float level;
		private Context context;
		private long lastUpdate;

		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			context = host.context();
		}

		@Override
		public void stop() {
		}

		@Override
		public void update(long now) {
			if (now - lastUpdate > MEDIA_VOLUME_UPDATE_INTERVAL) {
				level = getMediaVolumeLevel(context);
				lastUpdate = now;
			}
		}

		private static float getMediaVolumeLevel(@NonNull Context context) {
			AudioManager audioManager = (AudioManager) context.getSystemService(
					Context.AUDIO_SERVICE);
			if (audioManager == null) {
				return 0;
			}
			float maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
			float currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
			if (maxVolume <= 0 || currentVolume < 0) {
				return 0;
			}
			return currentVolume / maxVolume;
		}
	}

	static final class Microphone implements UniformSource {
		static final Key<Microphone> KEY = Microphone::new;

		boolean recording;
		float amplitude;
		private final float[] sample = new float[1];
		@Nullable
		private MicInputListener listener;
		@Nullable
		private Retry retry;
		private UniformSources sources;
		// Set on the main thread when the user did not allow recording.
		private volatile boolean denied;

		// Without the permission, or while the microphone is busy, it
		// tries again until it records.
		@Override
		public void start(@NonNull EngineHost host, @NonNull UniformSources sources) {
			this.sources = sources;
			var listener = new MicInputListener(host.context());
			this.listener = listener;
			if (listener.register()) {
				return;
			}
			if (!host.onPermissionNeeded(
					android.Manifest.permission.RECORD_AUDIO,
					() -> denied = true)) {
				sources.warn("The microphone may not record; run this " +
						"shader in the app once to allow it");
			}
			retry = new Retry(listener::register);
			retry.later();
		}

		@Override
		public void stop() {
			if (retry != null) {
				retry.stop();
				retry = null;
			}
			if (listener != null) {
				listener.unregister();
				listener = null;
			}
			recording = false;
		}

		@Override
		public void update(long now) {
			if (denied) {
				denied = false;
				sources.warn("The microphone permission is missing");
			}
			recording = listener != null && listener.isRecording();
			if (recording) {
				listener.sample.read(sample);
				amplitude = sample[0];
			}
		}
	}

	private SystemSources() {
	}
}
