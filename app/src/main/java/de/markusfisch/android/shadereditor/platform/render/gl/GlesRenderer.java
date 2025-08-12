package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES32;

import androidx.annotation.NonNull;

import de.markusfisch.android.shadereditor.engine.Renderer;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.pipeline.CommandBuffer;
import de.markusfisch.android.shadereditor.engine.pipeline.GpuCommand;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesCommandDispatcher;
import de.markusfisch.android.shadereditor.platform.render.gl.core.GlesRenderContext;
import de.markusfisch.android.shadereditor.platform.render.gl.core.ShaderCache;
import de.markusfisch.android.shadereditor.platform.render.gl.handlers.BeginPassHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.handlers.BindGeometryHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.handlers.BindProgramHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.handlers.BlitHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.handlers.DrawHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.handlers.EndPassHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.handlers.SetUniformsHandler;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesFramebufferManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesGeometryManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesTextureManager;

public final class GlesRenderer implements Renderer, ShaderIntrospector, AutoCloseable {
	private final GlesGeometryManager geometries = new GlesGeometryManager();
	private final GlesTextureManager textures = new GlesTextureManager();
	private final GlesFramebufferManager framebuffers = new GlesFramebufferManager(textures);
	private final ShaderCache shaders = new ShaderCache();
	private final GlesCommandDispatcher dispatcher;

	public GlesRenderer() {
		var binder = new GlesBinder(textures);
		dispatcher = new GlesCommandDispatcher()
				.register(new BeginPassHandler(framebuffers, binder))
				.register(new EndPassHandler())
				.register(new BindProgramHandler(shaders))
				.register(new SetUniformsHandler(binder))
				.register(new BindGeometryHandler(geometries))
				.register(new DrawHandler())
				.register(new BlitHandler(shaders, geometries, framebuffers, binder));
	}

	@Override
	public void onSurfaceCreated() {
		shaders.clear();
		framebuffers.destroy();
		textures.destroy();
		geometries.destroy();
		GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1f);
	}

	@Override
	public void onSurfaceChanged(int w, int h) {
		GLES32.glViewport(0, 0, w, h);
	}

	@Override
	public void execute(@NonNull CommandBuffer commands) {
		var ctx = new GlesRenderContext();
		for (GpuCommand c : commands.cmds()) dispatcher.dispatch(c, ctx);
	}

	@Override
	public void close() {
		shaders.clear();
	}

	@NonNull
	@Override
	public ShaderMetadata introspect(@NonNull ShaderAsset asset) {
		return shaders.introspect(asset);
	}
}