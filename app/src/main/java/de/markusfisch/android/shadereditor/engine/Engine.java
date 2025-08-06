package de.markusfisch.android.shadereditor.engine;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

import de.markusfisch.android.shadereditor.engine.data.DataKey;
import de.markusfisch.android.shadereditor.engine.data.DataProvider;
import de.markusfisch.android.shadereditor.engine.data.DataProviderManager;
import de.markusfisch.android.shadereditor.engine.model.RenderPass;

public class Engine {
	private final Deque<Plugin> plugins = new ArrayDeque<>();
	private final Deque<Plugin> addedPlugins = new ArrayDeque<>();
	private final Deque<RenderPass> renderQueue = new ArrayDeque<>();
	private final Deque<DataProvider<?>> addedDataProviders = new ArrayDeque<>();
	@NonNull
	private final DataProviderManager dataProviderManager;
	@Nullable
	private Renderer renderer;

	public Engine(@NonNull Context context) {
		this.dataProviderManager = new DataProviderManager(context);
	}

	public void setRenderer(@Nullable Renderer renderer) {
		this.renderer = renderer;
	}

	public void registerPlugin(@NonNull Plugin plugin) {
		addedPlugins.add(plugin);
	}

	@NonNull
	public Engine registerDataProvider(@NonNull DataProvider<?> provider) {
		addedDataProviders.add(provider);
		return this;
	}

	/**
	 * Retrieves data from the provider system.
	 *
	 * @param key The type-safe key for the data.
	 * @return The requested data, or null if not available.
	 */
	@Nullable
	public <T> T getData(@NonNull DataKey<T> key) {
		return dataProviderManager.getData(key);
	}

	/**
	 * Submits a render pass to be drawn this frame.
	 * This should be called by plugins during the onRender hook.
	 *
	 * @param renderPass The description of what to render.
	 */
	public void submit(@NonNull RenderPass renderPass) {
		renderQueue.add(renderPass);
	}

	private void processAddedPlugins() {
		Plugin plugin;
		while ((plugin = addedPlugins.poll()) != null) {
			plugins.add(plugin);
			plugin.onSetup(this);
		}
	}

	private void processAddedDataProviders() {
		DataProvider<?> provider;
		while ((provider = addedDataProviders.poll()) != null) {
			dataProviderManager.registerProvider(provider);
		}
	}

	public void setup() {
		var plugins = new ArrayDeque<>(this.plugins);
		plugins.addAll(addedPlugins);
		shutdown();
		if (renderer != null) {
			renderer.onSurfaceCreated();
		}
		addedPlugins.addAll(plugins);
		processAddedPlugins();
		processAddedDataProviders();
		dataProviderManager.onStart();
	}

	public void setViewport(int width, int height) {
		if (renderer != null) {
			renderer.onSurfaceChanged(width, height);
		}
	}

	public void renderFrame() {
		processAddedPlugins();
		processAddedDataProviders();

		if (renderer == null) {
			throw new IllegalStateException("Renderer has not been set.");
		}

		// 1. Pre-render hooks
		for (Plugin plugin : plugins) {
			plugin.onPreRender(this);
		}

		// 2. Clear last frame's work and ask plugins to queue this frame's work
		renderQueue.clear();
		for (Plugin plugin : plugins) {
			plugin.onRender(this);
		}

		// 3. Execute the queued render passes
		for (var pass : renderQueue) {
			renderer.render(pass);
		}

		// 4. Post-render hooks
		for (var plugin : plugins) {
			plugin.onPostRender(this);
		}
	}

	public void shutdown() {
		for (var plugin : plugins) {
			plugin.onTeardown(this);
		}
		plugins.clear();
		addedPlugins.clear();
		addedDataProviders.clear();
		renderQueue.clear();
		dataProviderManager.reset();
	}
}