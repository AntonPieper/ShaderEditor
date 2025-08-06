package de.markusfisch.android.shadereditor.renderengine.provider;

import androidx.annotation.NonNull;

import java.util.Set;

import de.markusfisch.android.shadereditor.renderengine.DataProvider;
import de.markusfisch.android.shadereditor.renderengine.FrameContext;
import de.markusfisch.android.shadereditor.renderengine.ProviderKey;
import de.markusfisch.android.shadereditor.renderengine.UniformTypes;

/**
 * A provider for common, time-sensitive uniforms like resolution and time.
 */
public class TimeAndResolutionProvider implements DataProvider {
	public static final ProviderKey<Float> KEY_TIME =
			new ProviderKey<>("time", Float.class, UniformTypes.FLOAT);
	public static final ProviderKey<float[]> KEY_RESOLUTION =
			new ProviderKey<>("resolution", float[].class, UniformTypes.VEC2);

	private final long startTime = System.currentTimeMillis();
	private final float[] resolution = new float[2];

	/**
	 * Updates the screen resolution. Typically called from onSurfaceChanged.
	 */
	public void setResolution(int width, int height) {
		this.resolution[0] = (float) width;
		this.resolution[1] = (float) height;
	}

	@NonNull
	@Override
	public Set<ProviderKey<?>> getProvidedKeys() {
		return Set.of(KEY_TIME, KEY_RESOLUTION);
	}

	@NonNull
	@Override
	public Set<ProviderKey<?>> getDependencies() {
		return Set.of();
	}

	@Override
	public void update(@NonNull FrameContext context) {
		// Calculate elapsed time in seconds
		float time = (System.currentTimeMillis() - startTime) / 1000.0f;
		context.put(KEY_TIME, time);
		context.put(KEY_RESOLUTION, resolution);
	}
}