package de.markusfisch.android.shadereditor.platform.render.gl;

import android.opengl.GLES32;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.markusfisch.android.shadereditor.engine.Renderer;
import de.markusfisch.android.shadereditor.engine.ShaderIntrospector;
import de.markusfisch.android.shadereditor.engine.Viewport;
import de.markusfisch.android.shadereditor.engine.asset.ShaderAsset;
import de.markusfisch.android.shadereditor.engine.error.EngineError;
import de.markusfisch.android.shadereditor.engine.error.EngineException;
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
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesSwapchainManager;
import de.markusfisch.android.shadereditor.platform.render.gl.managers.GlesTextureManager;

public final class GlesRenderer implements Renderer, ShaderIntrospector, AutoCloseable {
	private final static String TAG = "GlesRenderer";
	@Nullable
	ShaderIntrospector introspector;
	@Nullable
	private GlesCommandDispatcher dispatcher;
	@Nullable
	private GlesSwapchainManager swapchainManager;
	@Nullable
	private GlesGeometryManager geometryManager;
	@Nullable
	private GlesTextureManager textureManager;
	@Nullable
	private GlesFramebufferManager framebufferManager;
	@Nullable
	private ShaderCache shaderCache;
	@Nullable
	private Viewport lastViewport;

	@Override
	public void onSurfaceCreated() {
		// Ensure any previous resources are released before creating new ones.
		close();

		geometryManager = new GlesGeometryManager();
		textureManager = new GlesTextureManager();
		framebufferManager = new GlesFramebufferManager(textureManager);
		swapchainManager = new GlesSwapchainManager(textureManager, framebufferManager);
		shaderCache = new ShaderCache();
		this.introspector = shaderCache;

		var binder = new GlesBinder(textureManager, swapchainManager);
		dispatcher = new GlesCommandDispatcher()
				.register(new BeginPassHandler(framebufferManager, swapchainManager, binder))
				.register(new EndPassHandler())
				.register(new BindProgramHandler(shaderCache))
				.register(new SetUniformsHandler(binder))
				.register(new BindGeometryHandler(geometryManager))
				.register(new DrawHandler())
				.register(new BlitHandler(
						shaderCache,
						geometryManager,
						framebufferManager,
						swapchainManager,
						binder));

		GLES32.glClearColor(0.0f, 0.0f, 0.0f, 1f);
	}

	@Override
	public void onSurfaceChanged(int w, int h) {
		lastViewport = new Viewport(w, h);
		GLES32.glViewport(0, 0, w, h);
	}

	@Override
	public void execute(@NonNull CommandBuffer commands) {
		if (dispatcher == null || lastViewport == null) {
			// Don't try to render if the surface isn't fully initialized.
			return;
		}
		var ctx = new GlesRenderContext(lastViewport);
		for (GpuCommand c : commands.cmds()) {
			dispatcher.dispatch(c, ctx);
		}
	}

	@Override
	public void endFrame() {
		if (swapchainManager != null) {
			swapchainManager.swapAll();
		}
	}

	@Override
	public void close() {
		if (swapchainManager != null) swapchainManager.close();
		if (framebufferManager != null) framebufferManager.close();
		if (textureManager != null) textureManager.close();
		if (geometryManager != null) geometryManager.close();
		if (shaderCache != null) shaderCache.close();

		swapchainManager = null;
		framebufferManager = null;
		textureManager = null;
		geometryManager = null;
		shaderCache = null;
		introspector = null;
		dispatcher = null;
		Log.d(TAG, "GLES resources closed.");
	}

	@NonNull
	@Override
	public ShaderMetadata introspect(@NonNull ShaderAsset asset) {
		if (introspector == null) {
			throw new EngineException(new EngineError.GenericError(
					"Renderer not initialized: introspect called before onSurfaceCreated",
					null));
		}
		return introspector.introspect(asset);
	}

	@Nullable
	public byte[] readThumbnailPixels() {
		if (lastViewport == null || lastViewport.width() <= 0 || lastViewport.height() <= 0) {
			return null;
		}
		// Scale down to a thumbnail size.
		final int thumbWidth = 128;
		final int thumbHeight =
				(int) ((float) lastViewport.height() / lastViewport.width() * thumbWidth);
		if (thumbHeight <= 0) return null;


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
		GlesUtil.checkErrors("glReadPixels");

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
		return invertedPixels;
	}
}