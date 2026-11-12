package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.DatabaseProjectFiles;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.legacy.LegacyShaderConverter;

public class ShaderView extends GLSurfaceView {
	// Diagnostics of a converted database shader at the lines of its
	// text, with the fix of the old conventions for a missing texture.
	private record Stored(@Nullable LegacyShaderConverter.Result conversion)
			implements ShaderEngine.Reporting {
		@NonNull
		@Override
		public List<ProjectDiagnostic> locate(
				@NonNull List<ProjectDiagnostic> diagnostics) {
			return conversion != null
					? conversion.toOriginal(diagnostics)
					: diagnostics;
		}

		@NonNull
		@Override
		public String textureFix() {
			return LegacyShaderConverter.ADD_TEXTURE;
		}
	}

	// Converts and prepares the shaders of all views, one at a time.
	private static final ExecutorService PREPARER =
			Executors.newSingleThreadExecutor(runnable -> {
				var thread = new Thread(runnable, "ShaderPreparer");
				thread.setDaemon(true);
				return thread;
			});

	// The newest shader request that waits to be prepared.
	private final AtomicReference<Runnable> waiting = new AtomicReference<>();
	private ShaderEngine engine;
	// What the runtime of the shader set last belongs to.
	@Nullable
	private volatile Object runtimeOwner;
	// Written by the context factory on the GL thread.
	private int contextVersion = 2;

	public ShaderView(Context context, int renderMode) {
		super(context);
		init(context, renderMode);
	}

	public ShaderView(Context context) {
		super(context);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY);
	}

	public ShaderView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context, GLSurfaceView.RENDERMODE_CONTINUOUSLY);
	}

	@Override
	public void onPause() {
		// Queued events run before the GL thread pauses, while the last
		// frame is still there.
		queueEvent(engine::answerThumbnails);
		super.onPause();
		engine.onContextLost();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		engine.onAttached();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		waiting.set(null);
		engine.onDetached();
	}

	// Click handling is implemented in the engine.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		engine.pointer(event);
		return true;
	}

	// Shows the text of a database shader, such as the unsaved text of
	// one in the editor, at this quality.
	public void setShader(@NonNull String text, float quality) {
		setShader(text, quality, 0);
	}

	// Like setShader(text, quality), and the runtime starts at this value
	// once the shader replaces the one shown.
	public void setShader(
			@NonNull String text,
			float quality,
			long runtimeNanos) {
		var owner = new Object();
		runtimeOwner = owner;
		// Thumbnails and quality changes from now on are of this shader.
		int request = engine.request();
		// Converting reads the textures it names from the database.
		if (waiting.getAndSet(() -> prepare(request, text, quality, owner,
				runtimeNanos)) == null) {
			PREPARER.execute(() -> {
				var preparation = waiting.getAndSet(null);
				if (preparation != null) {
					preparation.run();
				}
			});
		}
	}

	// The runtime of the shader set last while it is shown, or -1.
	public long getRuntimeNanos() {
		var owner = runtimeOwner;
		return owner != null ? engine.getRuntimeNanos(owner) : -1;
	}

	// Applies to the shader set last, also while it is prepared.
	public void setQuality(float quality) {
		engine.setQuality(quality);
		requestRender();
	}

	@NonNull
	public ShaderEngine getEngine() {
		return engine;
	}

	private void prepare(
			int request,
			@NonNull String text,
			float quality,
			@NonNull Object owner,
			long runtimeNanos) {
		var limits = ProjectLimits.DEFAULT;
		Preparation preparation;
		LegacyShaderConverter.Result conversion = null;
		try {
			var reserved = UniformTable.names();
			var files = DatabaseProjectFiles.convert(Database.getInstance(
					getContext()).getDataSource().texture, text, limits,
					reserved);
			conversion = files.conversion();
			preparation = Preparation.prepare(files,
					LegacyShaderConverter.SHADER, limits, reserved);
		} catch (RuntimeException e) {
			preparation = new Preparation(null, List.of(ProjectDiagnostic.error(
					"Cannot load the textures: " + e.getMessage())),
					List.of(), Map.of());
		}
		if (engine.setPlan(request, preparation, quality,
				new Stored(conversion), owner, runtimeNanos)) {
			requestRender();
		}
	}

	private void init(Context context, int renderMode) {
		engine = new ShaderEngine(new ContextEngineHost(context));
		engine.setRenderRequester(this::requestRender);

		// On some devices it's important to setEGLContextClientVersion()
		// even if the docs say it's not used when setEGLContextFactory()
		// is called. Not doing so will crash the app (e.g. on the FP1).
		setEGLContextClientVersion(2);
		setEGLContextFactory(new ContextFactory());
		setRenderer(new Renderer() {
			@Override
			public void onSurfaceCreated(GL10 gl, EGLConfig config) {
				engine.onContextCreated(contextVersion);
			}

			@Override
			public void onSurfaceChanged(GL10 gl, int width, int height) {
				engine.onSurfaceChanged(width, height);
			}

			@Override
			public void onDrawFrame(GL10 gl) {
				engine.drawFrame();
			}
		});
		setRenderMode(renderMode);
	}

	private class ContextFactory implements GLSurfaceView.EGLContextFactory {
		@Override
		public EGLContext createContext(EGL10 egl, EGLDisplay display,
				EGLConfig eglConfig) {
			int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
			EGLContext context = egl.eglCreateContext(display, eglConfig,
					EGL10.EGL_NO_CONTEXT, new int[]{
							EGL_CONTEXT_CLIENT_VERSION,
							3,
							EGL10.EGL_NONE
					});
			if (context != null && context != EGL10.EGL_NO_CONTEXT &&
					context.getGL() != null) {
				contextVersion = 3;
				return context;
			}
			return egl.eglCreateContext(display, eglConfig,
					EGL10.EGL_NO_CONTEXT, new int[]{
							EGL_CONTEXT_CLIENT_VERSION,
							2,
							EGL10.EGL_NONE
					});
		}

		@Override
		public void destroyContext(EGL10 egl, EGLDisplay display,
				EGLContext context) {
			egl.eglDestroyContext(display, context);
		}
	}
}
