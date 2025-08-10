package de.markusfisch.android.shadereditor.engine;

import androidx.annotation.NonNull;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages the lifecycle of all registered plugins.
 */
class PluginManager {
	private final Deque<Plugin> plugins = new ArrayDeque<>();
	private final Deque<Plugin> addedPlugins = new ArrayDeque<>();

	/**
	 * Queues a plugin to be initialized in the next processing cycle.
	 */
	public void registerPlugin(@NonNull Plugin plugin) {
		addedPlugins.add(plugin);
	}

	/**
	 * Initializes any newly added plugins by calling their onSetup hook.
	 */
	public void setupPlugins(@NonNull Engine engine) {
		Plugin plugin;
		while ((plugin = addedPlugins.poll()) != null) {
			plugins.add(plugin);
			plugin.onSetup(engine);
		}
	}

	/**
	 * Invokes the onPreRender hook on all active plugins.
	 */
	public void preRender(@NonNull Engine engine) {
		for (Plugin plugin : plugins) {
			plugin.onPreRender(engine);
		}
	}

	/**
	 * Invokes the onRender hook on all active plugins.
	 */
	public void render(@NonNull Engine engine) {
		for (Plugin plugin : plugins) {
			plugin.onRender(engine);
		}
	}

	/**
	 * Invokes the onPostRender hook on all active plugins.
	 */
	public void postRender(@NonNull Engine engine) {
		for (var plugin : plugins) {
			plugin.onPostRender(engine);
		}
	}

	/**
	 * Invokes the onTeardown hook on all active plugins and clears the plugin registry.
	 */
	public void teardown(@NonNull Engine engine) {
		for (var plugin : plugins) {
			plugin.onTeardown(engine);
		}
		plugins.clear();
		addedPlugins.clear();
	}
}