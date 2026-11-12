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
import java.util.Objects;

import de.markusfisch.android.shadereditor.project.Preparation;
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
		// Called on the GL thread with a PNG of the shader set last when
		// it was asked for. Called with null when that frame could not be
		// encoded or that shader will not draw: it failed, a newer one
		// replaced it before it drew, or the view was detached, which
		// calls it on the detaching thread.
		void onThumbnail(@Nullable byte[] png);
	}

	// What a host set: the shader to build, what setShader() or setPlan()
	// got, which the runtime belongs to, and the runtime it starts at. A
	// quality change keeps the latter two.
	private record Offer(
			@NonNull EngineShader shader,
			@NonNull Object origin,
			long runtimeNanos) {
	}

	private static final long FPS_UPDATE_FREQUENCY_NS = 200000000L;
	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_REFRESH_RATE = 60f;
	private static final float DEFAULT_FTIME_MAX = 3f;

	private final GlDevice device = new GlDevice();
	private final ShaderRenderPipeline renderPipeline =
			new ShaderRenderPipeline(device);
	private final EngineInputs inputs = new EngineInputs();
	private final ShaderSwap<Offer, EngineBuild> shaders = new ShaderSwap<>();
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
		setShader(session, 0);
	}

	// Any thread. Like setShader(session), and the runtime starts at
	// this value once the shader replaces the one shown.
	public void setShader(
			@NonNull ShaderProjectSession session,
			long runtimeNanos) {
		shaders.offer(new Offer(new SessionShader(session), session,
				runtimeNanos));
	}

	// Any thread. Like setShader(), for a prepared project at a quality;
	// the diagnostics of its preparation come with those of its build.
	public void setPlan(@NonNull Preparation preparation, float quality) {
		shaders.offer(new Offer(new PlanShader(preparation, quality),
				preparation, 0));
	}

	// Any thread. The runtime of the shader set with this session while
	// it is shown, or -1.
	public long getRuntimeNanos(@NonNull ShaderProjectSession session) {
		return inputs.getRuntimeNanos(session);
	}

	// Any thread. Builds the shader set last again with this quality.
	public void setQuality(float quality) {
		shaders.offerChanged(offer -> new Offer(
				offer.shader().withQuality(quality),
				offer.origin(),
				offer.runtimeNanos()));
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
			createCurrentTargets();
		}
		// What a build decoded may depend on the render size.
		var stale = shaders.resized(width, height);
		if (stale != null) {
			loadTextures(stale);
		}

		resetFps();
	}

	// GL thread.
	public void drawFrame() {
		// Before a newer shader replaces the frame it was asked for.
		answerThumbnails();
		swapInNewestShader();
		answerThumbnails(thumbnails.drop(shaders::canDraw), null);
		var build = shaders.current();
		var revision = shaders.currentRevision();
		var builtins = activeBuiltins;
		if (build == null ||
				revision == null ||
				builtins == null ||
				!renderPipeline.hasSurfaceProgram() ||
				!renderPipeline.hasTargets()) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			return;
		}

		long now = System.nanoTime();
		var frame = inputs.capture(now, fTimeMax);
		builtins.write(frame, now);
		// A source that failed after it started, such as a camera that
		// did not open.
		if (sources.takeWarned()) {
			report(revision, new ArrayList<>(sources.getWarnings()));
		}
		device.bindEmptyTextures(build.program());
		device.bindTexture(backBufferUnit, renderPipeline.getBackTexture());
		build.draw(renderPipeline);
		renderPipeline.renderSurfacePass(
				frame.surfaceWidth,
				frame.surfaceHeight);
		renderPipeline.swapTargets();
		thumbnails.onFrame(revision.number());
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

	// After the host's view was detached. Like onContextLost(), and the
	// thumbnail requests still waiting are answered with null.
	public void onDetached() {
		discardContextResources();
		answerThumbnails(thumbnails.takeAll(), null);
	}

	public void pointer(@NonNull MotionEvent e) {
		inputs.post(e);
	}

	public void offset(float x, float y) {
		inputs.postOffset(x, y);
	}

	// Any thread. Answered after a complete frame of the shader set last,
	// or with null once that shader will not draw.
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
		var callbacks = thumbnails.take();
		if (!callbacks.isEmpty()) {
			answerThumbnails(callbacks, renderPipeline.captureThumbnail());
		}
	}

	private static void answerThumbnails(
			@NonNull List<ThumbnailCallback> callbacks,
			@Nullable byte[] png) {
		for (var callback : callbacks) {
			callback.onThumbnail(png);
		}
	}

	private void swapInNewestShader() {
		for (var revision = shaders.take();
				revision != null;
				revision = shaders.take()) {
			build(revision);
		}
		var next = shaders.next();
		if (next == null) {
			return;
		}
		var errors = new ArrayList<ProjectDiagnostic>();
		var textures = next.upload(errors);
		ShaderRenderPipeline.Targets targets = null;
		// Its targets come before the current build goes, so a build
		// that cannot draw leaves the current one drawing.
		if (!next.hasFailed() && next.isComplete()) {
			targets = renderPipeline.createTargets(
					EngineInputs.renderSize(inputs.getSurfaceWidth(), next.quality()),
					EngineInputs.renderSize(inputs.getSurfaceHeight(), next.quality()),
					next.backBufferSampling(),
					textures != null ? textures.backBuffer() : null,
					errors);
		}
		if (textures != null) {
			textures.recycle();
		}
		if (targets == null) {
			if (next.hasFailed() || next.isComplete()) {
				report(shaders.nextFailed(), errors);
			}
			return;
		}
		shaders.swapIfComplete();
		renderPipeline.useTargets(targets);
		thumbnails.onFramesLost();
		var shown = Objects.requireNonNull(shaders.currentRevision());
		inputs.showRuntime(
				shown.shader().origin(),
				shown.shader().runtimeNanos());

		long now = System.nanoTime();
		lastRender = now;
		inputs.restart(next.quality(), now);
		sources.setRenderSize(inputs.getRenderWidth(), inputs.getRenderHeight());
		configure(next);
		errors.addAll(sources.getWarnings());
		sources.takeWarned();
		resetFps();
		report(shown, errors);
	}

	// After a resize, the build drawing starts again with cleared targets.
	private void createCurrentTargets() {
		var build = shaders.current();
		if (build == null) {
			return;
		}
		var errors = new ArrayList<ProjectDiagnostic>();
		var targets = renderPipeline.createTargets(
				inputs.getRenderWidth(),
				inputs.getRenderHeight(),
				build.backBufferSampling(),
				null,
				errors);
		if (targets != null) {
			renderPipeline.useTargets(targets);
		} else {
			report(shaders.currentRevision(), errors);
		}
	}

	private void build(@NonNull ShaderSwap.Revision<Offer> revision) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var build = revision.shader().shader().link(device, version, errors);
		if (build == null) {
			shaders.failed(revision);
			report(revision, errors);
			return;
		}
		shaders.built(revision, build);
		loadTextures(build);
	}

	// For the render size of the surface at its quality.
	private void loadTextures(@NonNull EngineBuild build) {
		build.loadTextures(
				context,
				EngineInputs.renderSize(inputs.getSurfaceWidth(), build.quality()),
				EngineInputs.renderSize(inputs.getSurfaceHeight(), build.quality()),
				renderRequester);
	}

	// Only the outcome of the newest revision reported so far.
	private void report(
			@Nullable ShaderSwap.Revision<Offer> revision,
			@NonNull List<ProjectDiagnostic> errors) {
		if (revision != null && shaders.report(revision)) {
			submitErrors(errors);
		}
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

	private void configure(@NonNull EngineBuild build) {
		fTimeMax = build.fTimeMax();
		backBufferUnit = build.program().getTextureUnit(UniformTable.BACKBUFFER);
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
	private void runSources(@Nullable EngineBuild build) {
		if (build == null) {
			activeBuiltins = null;
			sources.stop();
			sources.setCameraBinding(null);
			return;
		}
		sources.setCameraBinding(build.getCameraBinding());
		activeBuiltins = new ActiveBuiltins(device, build.program(), sources);
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
