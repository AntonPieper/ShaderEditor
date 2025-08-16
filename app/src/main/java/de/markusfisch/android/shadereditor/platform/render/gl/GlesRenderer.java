package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES32;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.markusfisch.android.shadereditor.engine.Renderer;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.Viewport;
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

	@Nullable
	private Viewport lastViewport;
	@Nullable
	private byte[] thumbnail;

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
		lastViewport = null;
		thumbnail = null;
		GLES32.glClearColor(0.1f, 0.1f, 0.1f, 1f);
	}

	@Override
	public void onSurfaceChanged(int w, int h) {
		lastViewport = new Viewport(w, h);
		GLES32.glViewport(0, 0, w, h);
	}

	@Override
	public void execute(@NonNull CommandBuffer commands) {
		var ctx = new GlesRenderContext();
		for (GpuCommand c : commands.cmds()) {
			dispatcher.dispatch(c, ctx);
		}
		captureThumbnail();
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

	@Nullable
	public byte[] getThumbnail() {
		return thumbnail;
	}

	private void captureThumbnail() {
		if (lastViewport == null || lastViewport.width() <= 0 || lastViewport.height() <= 0) {
			return;
		}
		// Scale down to a thumbnail size.
		final int thumbWidth = 128;
		final int thumbHeight =
				(int) ((float) lastViewport.height() / lastViewport.width() * thumbWidth);
		if (thumbHeight <= 0) return;


		final int size = thumbWidth * thumbHeight * 4;
		final ByteBuffer buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
		GLES32.glReadPixels(
				(lastViewport.width() - thumbWidth) / 2,
				(lastViewport.height() - thumbHeight) / 2,
				thumbWidth,
				thumbHeight,
				GLES32.GL_RGBA,
				GLES32.GL_UNSIGNED_BYTE,
				buffer);

		// Invert vertically because of OpenGL's coordinate system.
		byte[] pixels = new byte[size];
		byte[] invertedPixels = new byte[size];
		buffer.get(pixels);
		for (int y = 0; y < thumbHeight; y++) {
			System.arraycopy(
					pixels, y * thumbWidth * 4,
					invertedPixels, (thumbHeight - 1 - y) * thumbWidth * 4,
					thumbWidth * 4
			);
		}
		this.thumbnail = invertedPixels;
	}
}