package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

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
import de.markusfisch.android.shadereditor.opengl.Fit;
import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.opengl.Tuning;
import de.markusfisch.android.shadereditor.opengl.UniformTable;
import de.markusfisch.android.shadereditor.project.DatabaseProjectFiles;
import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectLimits;
import de.markusfisch.android.shadereditor.project.ProjectPath;
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
	// What the shaders set as text, and plans set without a picture, are
	// revisions of: they keep what is shown when they fail.
	private static final Object ONE_PICTURE = new Object();
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
	// The render mode the host asked for, and whether the picture is
	// paused, which draws only on demand.
	private int requestedRenderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY;
	private boolean paused;
	// The most frames a second, 0 for as fast as the screen, and whether
	// the view is resumed, which a limit's frames wait for.
	private int frameRateLimit;
	private boolean resumed = true;
	// Not attached only after it was detached: the wallpaper's view is
	// never in a window.
	private boolean attached = true;
	private boolean pacing;
	// Asks for the next frame on the first display frame after a frame
	// of the limit is over: the GL thread draws only then.
	private final Choreographer.FrameCallback pace = frameTimeNanos -> {
		if (pacing) {
			requestRender();
			schedulePace(frameTimeNanos);
		}
	};

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
		resumed = false;
		applyRenderMode();
	}

	@Override
	public void onResume() {
		super.onResume();
		resumed = true;
		applyRenderMode();
	}

	// Draws at most limit frames a second, as fast as the screen for 0:
	// the view then draws on demand, asked from the display's frame
	// callback once a frame of the limit is over, and on Android 11 and
	// later tells its surface the rate, so a display that can go slower
	// may. timeDelta and frameRate say what really happens. Main thread.
	public void setFrameRateLimit(int limit) {
		limit = Math.max(0, limit);
		if (limit != frameRateLimit) {
			// The rate it tells was of the limit before.
			engine.paceChanged();
		}
		frameRateLimit = limit;
		applyRenderMode();
		tellFrameRate();
	}

	@Override
	public void surfaceCreated(@NonNull SurfaceHolder holder) {
		super.surfaceCreated(holder);
		tellFrameRate();
	}

	@Override
	protected void onAttachedToWindow() {
		super.onAttachedToWindow();
		engine.onAttached();
		attached = true;
		applyRenderMode();
	}

	@Override
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		waiting.set(null);
		engine.onDetached();
		// A detached view asks for no frames, nor keeps its callback,
		// which would keep the view.
		attached = false;
		applyRenderMode();
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

	// Shows a prepared project at this quality. Its plans are revisions
	// of one picture, as the shaders set as text are.
	public void setPlan(@NonNull Preparation preparation, float quality) {
		setPlan(preparation, quality, ONE_PICTURE);
	}

	// Shows a prepared project at this quality, a revision of picture:
	// if it fails, what is shown stays only when it is of picture too.
	public void setPlan(@NonNull Preparation preparation, float quality,
			@NonNull Object picture) {
		runtimeOwner = preparation;
		engine.setPlan(preparation, quality, picture);
		requestRender();
	}

	// Like setPlan(preparation, quality, picture), for a build of a
	// tuning session, which goes on from the one shown: see
	// ShaderEngine.continuePlan(). The runtime goes on for its owner.
	public void continuePlan(@NonNull Preparation preparation, float quality,
			@NonNull Object picture, @Nullable Tuning tuning) {
		engine.continuePlan(preparation, quality, picture, tuning);
		requestRender();
	}

	// Pauses the picture at the frame drawn last, or lets it run on:
	// paused, the view draws only on demand. See ShaderEngine.setPaused().
	public void setPaused(boolean paused) {
		this.paused = paused;
		applyRenderMode();
		engine.setPaused(paused);
	}

	// While paused, draws the next frame: see ShaderEngine.step().
	public void step() {
		engine.step();
	}

	// Draws the first frames again from time 0: see
	// ShaderEngine.restart().
	public void restart() {
		engine.restart();
	}

	// The mode a host asks for is kept for when the view plays again.
	@Override
	public void setRenderMode(int renderMode) {
		requestedRenderMode = renderMode;
		applyRenderMode();
	}

	private void applyRenderMode() {
		super.setRenderMode(renderMode(paused, frameRateLimit,
				requestedRenderMode));
		boolean pace = paces(paused, frameRateLimit, requestedRenderMode,
				resumed, attached);
		if (pace != pacing) {
			pacing = pace;
			if (pace) {
				requestRender();
				schedulePace(System.nanoTime());
			} else {
				Choreographer.getInstance().removeFrameCallback(this.pace);
			}
		}
	}

	// The render mode for the mode a host asks for: on demand while
	// paused, or while a limit asks for the frames.
	static int renderMode(boolean paused, int limit, int requested) {
		return paused || paced(paused, limit, requested)
				? GLSurfaceView.RENDERMODE_WHEN_DIRTY
				: requested;
	}

	// Whether frames are asked for by a limit: not while paused, nor
	// when the host asked for frames on demand only, nor while the view
	// is paused or detached.
	static boolean paces(boolean paused, int limit, int requested,
			boolean resumed, boolean attached) {
		return paced(paused, limit, requested) && resumed && attached;
	}

	private static boolean paced(boolean paused, int limit, int requested) {
		return !paused && limit > 0 &&
				requested == GLSurfaceView.RENDERMODE_CONTINUOUSLY;
	}

	// The next frame of the limit, on the first display frame after one
	// frame of the limit from the display frame at frameTimeNanos: timed
	// from that display frame, not from when its callback ran, so a late
	// callback does not make the next frame late; a quarter of a 60 Hz
	// frame early, as display frames do not come exactly evenly.
	private void schedulePace(long frameTimeNanos) {
		long due = frameTimeNanos + 1000000000L / frameRateLimit - 4000000L;
		Choreographer.getInstance().postFrameCallbackDelayed(pace,
				Math.max(0, (due - System.nanoTime()) / 1000000L));
	}

	// The rate the surface is drawn at, so a display that can go slower
	// may: 0 for no preference.
	private void tellFrameRate() {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
			return;
		}
		Surface surface = getHolder().getSurface();
		if (surface != null && surface.isValid()) {
			surface.setFrameRate(frameRateLimit,
					Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
		}
	}

	// Maps the pass of fit as it says while it is on the screen, and its
	// small picture; null for none. See ShaderEngine.setFit().
	public void setFit(@Nullable Fit fit) {
		engine.setFit(fit);
	}

	// Lets watcher hear small pictures of the passes, or no one for
	// null: see ShaderEngine.watchPasses().
	public void watchPasses(@Nullable ShaderEngine.PassWatcher watcher) {
		engine.watchPasses(watcher);
	}

	// Shows the pass of file instead of the output, or the output for
	// null: see ShaderEngine.showPass().
	public void showPass(@Nullable ProjectPath file) {
		engine.showPass(file);
	}

	// After the value of the tuning set with the plan changed: the GL
	// thread writes it before the next frame, which is drawn.
	public void applyTuning() {
		queueEvent(engine::applyTuning);
		requestRender();
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
			var builtins = UniformTable.builtins();
			var files = DatabaseProjectFiles.convert(Database.getInstance(
					getContext()).getDataSource().texture, text, limits,
					builtins.names());
			conversion = files.conversion();
			preparation = Preparation.prepare(files,
					LegacyShaderConverter.SHADER, limits, builtins);
		} catch (RuntimeException e) {
			preparation = new Preparation(null, List.of(ProjectDiagnostic.error(
					"Cannot load the textures: " + e.getMessage())),
					List.of(), List.of(), Map.of());
		}
		if (engine.setPlan(request, preparation, quality,
				new Stored(conversion), owner, runtimeNanos, ONE_PICTURE)) {
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
