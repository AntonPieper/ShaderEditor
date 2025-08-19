package de.markusfisch.android.shadereditor.platform.plugin;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.data.BackbufferDataKeys;
import de.markusfisch.android.shadereditor.engine.data.DataProvider;
import de.markusfisch.android.shadereditor.engine.scene.FrameSwapchain;
import de.markusfisch.android.shadereditor.engine.scene.RenderTarget;
import de.markusfisch.android.shadereditor.engine.scene.TextureSource;

/**
 * A platform plugin that provides a default backbuffer service.
 * <p>
 * It creates a single, logical {@link FrameSwapchain} and registers providers
 * that allow other parts of the engine to access its "read" texture and its
 * "write" render target via the data provider system.
 */
public class BackbufferPlugin implements Plugin {
	private static final String DEFAULT_BACKBUFFER_NAME = "default_backbuffer";

	@Override
	public void onSetup(@NonNull Engine engine) {
		// Create the single, logical handle for the swapchain. The renderer will
		// manage the actual GPU resources associated with this handle.
		final var backbuffer = new FrameSwapchain(DEFAULT_BACKBUFFER_NAME);

		// The "read" source for the backbuffer uniform is always the result
		// from the previous frame.
		final var textureSource = new TextureSource.FromSwapchain(backbuffer);

		// The "write" target for a feedback pass is the next texture in the swapchain.
		final var renderTarget = new RenderTarget.ToSwapchain(backbuffer);

		// Register providers so any plugin can request these resources.
		engine.registerProviderFactory(
				BackbufferDataKeys.BACKBUFFER_TEXTURE,
				() -> new ConstantProvider<>(textureSource)
		).registerProviderFactory(
				BackbufferDataKeys.BACKBUFFER_TARGET,
				() -> new ConstantProvider<>(renderTarget)
		);
	}

	/**
	 * A simple, stateless data provider that always returns a constant value.
	 */
	private static class ConstantProvider<T> implements DataProvider<T> {
		private final T value;

		ConstantProvider(T value) {
			this.value = value;
		}

		@Override
		public void start() {
			// Nothing to start
		}

		@Override
		public void stop() {
			// Nothing to stop
		}

		@NonNull
		@Override
		public T getValue() {
			return value;
		}
	}
}