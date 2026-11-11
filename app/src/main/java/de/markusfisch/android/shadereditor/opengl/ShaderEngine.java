package de.markusfisch.android.shadereditor.opengl;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.opengl.GLES20;
import android.os.Build;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ShaderProjectSession;

// The only class a host talks to. Hosts set the shader and post input
// from any thread; the GL thread calls the context, surface and frame
// methods.
public final class ShaderEngine {
	public interface Listener {
		// Called on the GL thread; empty when the shader built.
		void onDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics);

		void onFramesPerSecond(int fps);
	}

	public interface ThumbnailCallback {
		// Called on the GL thread with a PNG, or null when the frame
		// could not be encoded.
		void onThumbnail(@Nullable byte[] png);
	}

	private static final long FPS_UPDATE_FREQUENCY_NS = 200000000L;
	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_REFRESH_RATE = 60f;
	private static final float DEFAULT_FTIME_MAX = 3f;

	private final GlDevice device = new GlDevice();
	private final ShaderRenderPipeline renderPipeline =
			new ShaderRenderPipeline(device);
	private final EngineInputs inputs = new EngineInputs();
	private final ShaderSwap<ShaderProjectSession, ShaderBuild> shaders =
			new ShaderSwap<>();
	private final UniformSources sources;
	private final Context context;
	private final ThumbnailRequests<ThumbnailCallback> thumbnails =
			new ThumbnailRequests<>();

	@Nullable
	private Listener listener;
	@Nullable
	private volatile Runnable renderRequester;
	@Nullable
	private ActiveBuiltins activeBuiltins;
	private int version = 2;
	private int backBufferUnit = GlUniform.NO_UNIT;
	private float fTimeMax = DEFAULT_FTIME_MAX;
	private long lastRender;
	private volatile long nextFpsUpdate;
	private volatile float sum;
	private volatile float samples;
	private volatile int lastFps;
	private volatile float refreshRate = DEFAULT_REFRESH_RATE;

	public ShaderEngine(@NonNull EngineHost host) {
		context = host.context();
		sources = new UniformSources(host);
	}

	// Any thread. At a frame boundary, the GL thread builds the newest
	// shader while the current one keeps drawing, and swaps it in once
	// it links and its textures are uploaded.
	public void setShader(@NonNull ShaderProjectSession session) {
		inputs.setRuntimeNanos(0);
		shaders.offer(session);
	}

	public long getRuntimeNanos() {
		return inputs.getRuntimeNanos();
	}

	public void setRuntimeNanos(long nanos) {
		inputs.setRuntimeNanos(nanos);
	}

	// Any thread. Builds the shader set last again with this quality.
	public void setQuality(float quality) {
		var session = shaders.latest();
		if (session != null) {
			shaders.offer(session.withQuality(quality));
		}
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	// Called when decoded textures are ready, so a view that renders only
	// when dirty draws the frame that waited for them.
	public void setRenderRequester(@Nullable Runnable requester) {
		renderRequester = requester;
	}

	// GL thread, for every new context of the given GLES major version.
	public void onContextCreated(int version) {
		this.version = version;
		device.disable(GLES20.GL_CULL_FACE);
		device.disable(GLES20.GL_BLEND);
		device.disable(GLES20.GL_DEPTH_TEST);
		device.setClearColor(0f, 0f, 0f, 1f);

		discardContextResources();
		device.createContextResources();
		var errors = renderPipeline.createContextResources();
		if (!errors.isEmpty()) {
			submitErrors(errors);
		}
	}

	// GL thread.
	public void onSurfaceChanged(int width, int height) {
		long now = System.nanoTime();
		lastRender = now;
		refreshRate = getRefreshRate(context);
		boolean renderSizeChanged = inputs.resize(width, height, now);
		sources.setDeviceRotation(getDeviceRotation(context));
		sources.setRenderSize(inputs.getRenderWidth(), inputs.getRenderHeight());
		var camera = sources.get(CameraSource.KEY);
		if (camera != null) {
			camera.reopen();
		}
		if (renderSizeChanged) {
			renderPipeline.releaseTargets();
			thumbnails.onFramesLost();
			// The backbuffer preset is tiled to the render size.
			var build = shaders.current();
			if (build != null &&
					build.source.getBackBufferParameters().hasPreset()) {
				shaders.rebuildCurrent();
			}
		}

		resetFps();
	}

	// GL thread.
	public void drawFrame() {
		// Before a newer shader replaces the frame it was asked for.
		answerThumbnails();
		swapInNewestShader();
		var build = shaders.current();
		var builtins = activeBuiltins;
		if (build == null ||
				builtins == null ||
				!renderPipeline.hasSurfaceProgram()) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			return;
		}

		if (!renderPipeline.hasTargets()) {
			var targetErrors = renderPipeline.ensureTargets(
					inputs.getRenderWidth(),
					inputs.getRenderHeight(),
					build.source.getBackBufferSampling(),
					null);
			if (!targetErrors.isEmpty()) {
				submitErrors(targetErrors);
			}
			if (!renderPipeline.hasTargets()) {
				return;
			}
		}

		long now = System.nanoTime();
		var frame = inputs.capture(now, fTimeMax);
		builtins.write(frame, now);
		device.bindEmptyTextures(build.program);
		device.bindTexture(backBufferUnit, renderPipeline.getBackTexture());
		build.textures.bind(device);

		renderPipeline.renderMainPass(build.program);
		renderPipeline.renderSurfacePass(
				frame.surfaceWidth,
				frame.surfaceHeight);
		renderPipeline.swapTargets();
		thumbnails.onFrame();
		answerThumbnails();

		if (listener != null) {
			updateFps(now);
		}
	}

	// After the host released the context, when its view was detached
	// or paused; the GL thread is not running.
	public void onContextLost() {
		discardContextResources();
	}

	public void pointer(@NonNull MotionEvent e) {
		inputs.post(e);
	}

	public void offset(float x, float y) {
		inputs.postOffset(x, y);
	}

	// Any thread. Answered after a complete frame of the shader set last,
	// or of a newer one.
	public void requestThumbnail(@NonNull ThumbnailCallback callback) {
		thumbnails.add(shaders.offered(), callback);
		var requester = renderRequester;
		if (requester != null) {
			requester.run();
		}
	}

	// GL thread. Answers the thumbnail requests the frame drawn last can
	// answer; the driver also calls it before the GL thread pauses.
	public void answerThumbnails() {
		var callbacks = thumbnails.take(shaders.shown());
		if (callbacks.isEmpty()) {
			return;
		}
		byte[] png = renderPipeline.captureThumbnail();
		for (var callback : callbacks) {
			callback.onThumbnail(png);
		}
	}

	private void swapInNewestShader() {
		var session = shaders.take();
		if (session != null) {
			build(session);
		}
		var next = shaders.next();
		if (next == null) {
			return;
		}
		var errors = new ArrayList<ProjectDiagnostic>();
		var textures = next.upload(errors);
		if (shaders.swapIfComplete() == null) {
			return;
		}
		thumbnails.onFramesLost();

		long now = System.nanoTime();
		lastRender = now;
		inputs.restart(next.quality, now);
		sources.setRenderSize(inputs.getRenderWidth(), inputs.getRenderHeight());
		configure(next);
		renderPipeline.releaseTargets();
		errors.addAll(renderPipeline.ensureTargets(
				inputs.getRenderWidth(),
				inputs.getRenderHeight(),
				next.source.getBackBufferSampling(),
				textures != null ? textures.backBuffer() : null));
		if (textures != null) {
			textures.recycle();
		}
		resetFps();
		submitErrors(errors);
	}

	private void build(@NonNull ShaderProjectSession session) {
		var errors = new ArrayList<ProjectDiagnostic>();
		float quality = session.getQuality();
		// Text pasted from other apps may carry non-ASCII characters
		// that crash some shader compilers.
		var build = ShaderBuild.link(
				device,
				removeNonAscii(session.getEntryPointSource()),
				quality,
				version,
				errors);
		if (build == null) {
			shaders.failed();
			submitErrors(errors);
			return;
		}
		shaders.built(build);
		build.loadTextures(
				context,
				EngineInputs.renderSize(inputs.getSurfaceWidth(), quality),
				EngineInputs.renderSize(inputs.getSurfaceHeight(), quality),
				renderRequester);
	}

	private void resetFps() {
		sum = 0;
		samples = 0;
		lastFps = 0;
		nextFpsUpdate = 0;
	}

	private void submitErrors(@NonNull List<ProjectDiagnostic> errors) {
		if (listener != null) {
			listener.onDiagnostics(errors);
		}
	}

	private void configure(@NonNull ShaderBuild build) {
		fTimeMax = build.source.getFTimeMax();
		backBufferUnit = build.program.getTextureUnit(UniformTable.BACKBUFFER);
		build.textures.resolveUnits(build.program);
		runSources(build);
	}

	private void clearConfiguration() {
		inputs.pauseRuntime();
		fTimeMax = DEFAULT_FTIME_MAX;
		backBufferUnit = GlUniform.NO_UNIT;
		runSources(null);
	}

	// The one place that decides which sources run: the ones the current
	// shader reads, and none without one. A lost context, a detached
	// view and a host in the background all end the current shader.
	private void runSources(@Nullable ShaderBuild build) {
		if (build == null) {
			activeBuiltins = null;
			sources.stop();
			sources.setCameraBinding(null);
			return;
		}
		sources.setCameraBinding(build.textures.getFirstBinding(
				UniformTable.CAMERA_BACK,
				UniformTable.CAMERA_FRONT));
		activeBuiltins = new ActiveBuiltins(device, build.program, sources);
	}

	private void discardContextResources() {
		clearConfiguration();
		shaders.contextLost();
		renderPipeline.discardContextResources();
		thumbnails.onFramesLost();
	}

	private void updateFps(long now) {
		long delta = now - lastRender;

		synchronized (this) {
			sum += Math.min(NS_PER_SECOND / delta, refreshRate);

			if (++samples > 0xffff) {
				sum = sum / samples;
				samples = 1;
			}
		}

		if (now > nextFpsUpdate) {
			int fps = Math.round(sum / samples);
			if (fps != lastFps && listener != null) {
				listener.onFramesPerSecond(fps);
				lastFps = fps;
			}
			nextFpsUpdate = now + FPS_UPDATE_FREQUENCY_NS;
		}

		lastRender = now;
	}

	@NonNull
	private static String removeNonAscii(@NonNull String text) {
		return text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", "");
	}

	private static int getDeviceRotation(@NonNull Context context) {
		WindowManager windowManager = (WindowManager) context.getSystemService(
				Context.WINDOW_SERVICE);
		if (windowManager == null) {
			return 0;
		}

		return windowManager.getDefaultDisplay().getRotation();
	}

	private static float getRefreshRate(Context context) {
		float refreshRate = DEFAULT_REFRESH_RATE;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Display display = getDisplay(context);
			if (display != null && display.getMode() != null) {
				refreshRate = display.getMode().getRefreshRate();
			}
		} else {
			WindowManager windowManager = (WindowManager) context
					.getSystemService(Context.WINDOW_SERVICE);
			if (windowManager != null) {
				refreshRate = windowManager.getDefaultDisplay()
						.getRefreshRate();
			}
		}
		return refreshRate > 0f ? refreshRate : DEFAULT_REFRESH_RATE;
	}

	@SuppressLint("UseRequiresApi")
	@TargetApi(Build.VERSION_CODES.R)
	private static Display getDisplay(Context context) {
		try {
			return context.getDisplay();
		} catch (UnsupportedOperationException e) {
			// Fall through. Thrown when the context isn't associated
			// with a display.
		}
		DisplayManager displayManager = context.getSystemService(
				DisplayManager.class);
		if (displayManager == null) {
			return null;
		}
		return displayManager.getDisplay(Display.DEFAULT_DISPLAY);
	}
}
