package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.opengl.GLSurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.function.Supplier;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import de.markusfisch.android.shadereditor.engine.Engine;
import de.markusfisch.android.shadereditor.engine.Plugin;
import de.markusfisch.android.shadereditor.engine.renderer.GlesRenderer;

/**
 * A thin bridge between the Android GLSurfaceView and the rendering Engine.
 * Its sole responsibility is to delegate rendering lifecycle events to the engine.
 */
public class EngineRendererBridge implements GLSurfaceView.Renderer {

	@NonNull
	private final Context context;
	private final Collection<Supplier<Plugin>> plugins;
	@Nullable
	private Engine engine;

	/**
	 * Constructs the bridge.
	 *
	 * @param plugins A list of plugins to be registered with the engine.
	 */
	public EngineRendererBridge(@NonNull Context context,
			@NonNull Collection<Supplier<Plugin>> plugins) {
		this.context = context;
		this.plugins = plugins;
	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		if (engine != null) {
			engine.shutdown();
		}
		engine = new Engine(context);
		for (var plugin : plugins) {
			engine.registerPlugin(plugin.get());
		}
		// 1. Create the concrete renderer and give it to the engine
		GlesRenderer renderer = new GlesRenderer();
		engine.setRenderer(renderer);

		// 2. Forward the setup call to the engine
		engine.setup();
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
		if (engine != null) {
			engine.setViewport(width, height);
		}
	}

	@Override
	public void onDrawFrame(GL10 gl) {
		if (engine != null) {
			engine.renderFrame();
		}
	}

	public void destroy() {
		if (engine != null) {
			engine.shutdown();
			engine = null;
		}
	}
}