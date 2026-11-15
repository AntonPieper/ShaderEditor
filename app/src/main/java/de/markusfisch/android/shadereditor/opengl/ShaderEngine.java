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

// The only class a host talks to. Hosts set the plan and post input
// from any thread; the GL thread calls the context, surface and frame
// methods.
public final class ShaderEngine {
	public interface Listener {
		// Called on the GL thread; empty when the shader built.
		void onDiagnostics(@NonNull List<ProjectDiagnostic> diagnostics);

		// Called on the GL thread before onDiagnostics: whether the
		// picture drawn is an earlier revision's, the last that worked,
		// while the diagnostics are of a newer one that did not.
		default void onLastPicture(boolean shown) {
		}

		// The frames drawn per second, 0 when no picture runs any more.
		void onFramesPerSecond(int fps);
	}

	public interface ThumbnailCallback {
		// Called on the GL thread with a PNG of the shader requested last
		// when it was asked for. Called with null when that frame could
		// not be encoded or that shader will not draw: it failed or lost
		// its targets, a newer one replaced it before it drew, or the view
		// was detached, which calls it on the detaching or asking thread.
		void onThumbnail(@Nullable byte[] png);
	}

	// How a host shows what the engine reports about a plan it set.
	public interface Reporting {
		// The diagnostics where the user edits what they are about.
		@NonNull
		List<ProjectDiagnostic> locate(@NonNull List<ProjectDiagnostic> diagnostics);

		// How a sampler that reads no image gets one, formatted with its
		// name.
		@NonNull
		String textureFix();
	}

	// What a host set: the shader to build, what the runtime belongs to,
	// the runtime it starts at, how its diagnostics are shown, which
	// picture it is a revision of, and whether it goes on from the build
	// that draws. A quality change keeps the latter five.
	private record Offer(
			@NonNull PlanShader shader,
			@NonNull Object origin,
			long runtimeNanos,
			@NonNull Reporting reporting,
			@NonNull Object picture,
			boolean continues) {
	}

	// The ceiling of PictureLimit a host asked for, of 1, and whether the
	// picture eases there.
	private record Limit(float ceiling, boolean fade) {
	}

	// Diagnostics in the project files, with the fix for project files.
	private static final Reporting AS_PREPARED = new Reporting() {
		@NonNull
		@Override
		public List<ProjectDiagnostic> locate(
				@NonNull List<ProjectDiagnostic> diagnostics) {
			return diagnostics;
		}

		@NonNull
		@Override
		public String textureFix() {
			return UniformWarnings.ADD_INPUT;
		}
	};

	private static final long FPS_UPDATE_FREQUENCY_NS = 200000000L;
	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_REFRESH_RATE = 60f;
	private static final float DEFAULT_FTIME_MAX = 3f;

	private final GlDevice device = new GlDevice();
	private final ShaderRenderPipeline renderPipeline =
			new ShaderRenderPipeline(device);
	private final EngineInputs inputs = new EngineInputs();
	private final ShaderSwap<Offer, PlanRuntime> shaders = new ShaderSwap<>(
			(a, b) -> a.picture().equals(b.picture()));
	private final UniformSources sources;
	private final Context context;
	private final ThumbnailRequests<ThumbnailCallback> thumbnails =
			new ThumbnailRequests<>();

	@Nullable
	private volatile Listener listener;
	@Nullable
	private volatile Runnable renderRequester;
	@Nullable
	private ActiveBuiltins activeBuiltins;
	// What the build of the current revision reported when it was swapped
	// in, still valid when a source warns later.
	@NonNull
	private List<ProjectDiagnostic> built = List.of();
	@NonNull
	private TargetFormats formats = TargetFormats.of(2, "");
	private float fTimeMax = DEFAULT_FTIME_MAX;
	private long lastRender;
	private volatile long nextFpsUpdate;
	private volatile float sum;
	private volatile float samples;
	private volatile int lastFps;
	private volatile float refreshRate = DEFAULT_REFRESH_RATE;
	@NonNull
	private volatile Limit limit = new Limit(1f, false);
	// The limit the frames drawn last ease to, the ceiling they eased
	// from, the one they are at, and since when.
	@Nullable
	private Limit limitShown;
	private float limitFrom;
	private float limitCeiling = 1f;
	private long limitSince;

	public ShaderEngine(@NonNull EngineHost host) {
		context = host.context();
		sources = new UniformSources(host);
	}

	// Any thread. A prepared project at a quality, a revision of picture,
	// which is equal for the revisions of what runs, such as the edits of
	// one file; the diagnostics of its preparation come with those of its
	// build. At a frame boundary, the GL thread builds the newest plan
	// while the current one keeps drawing, and swaps it in once it links
	// and its images are uploaded. If it fails, the current one keeps
	// drawing when it is of the same picture, and goes otherwise.
	public void setPlan(@NonNull Preparation preparation, float quality,
			@NonNull Object picture) {
		setPlan(request(), preparation, quality, AS_PREPARED, preparation, 0,
				picture);
	}

	// Any thread. Starts a revision whose plan is still being prepared,
	// before the host prepares it, and returns its number. From now on,
	// thumbnails are of this revision, quality changes apply to it, and
	// plans of older revisions are not built anymore.
	public int request() {
		return shaders.request();
	}

	// Any thread. Like setPlan(preparation, quality, picture), for the
	// revision request() returned, with diagnostics shown as reporting
	// says, and the runtime of owner, which starts at runtimeNanos once
	// the plan replaces the one shown. Returns false and drops the plan
	// when a newer revision was requested meanwhile.
	public boolean setPlan(
			int request,
			@NonNull Preparation preparation,
			float quality,
			@NonNull Reporting reporting,
			@NonNull Object owner,
			long runtimeNanos,
			@NonNull Object picture) {
		return shaders.offer(request, new Offer(
				new PlanShader(preparation, quality, reporting.textureFix()),
				owner, runtimeNanos, reporting, picture, false));
	}

	// Any thread. Like setPlan(preparation, quality, picture), for the
	// builds of one tuning session, which go on from the build that
	// draws instead of starting again: time, frame, timeDelta and
	// frameRate go on, and so do the previous frames of the passes when
	// every target stays as it is (no pass changed its size or format).
	// It starts again as any build does when it is of another picture or
	// quality than the build that draws, or nothing draws.
	//
	// tuning, if not null, is the uniform that replaced a tuned number
	// of the text: the build declares it, and once it draws,
	// applyTuning() writes new values into it. tuning hears whether the
	// build draws or was refused; a refusal goes to tuning only, not to
	// the listener, and the build before keeps drawing.
	public void continuePlan(@NonNull Preparation preparation, float quality,
			@NonNull Object picture, @Nullable Tuning tuning) {
		shaders.offer(request(), new Offer(
				new PlanShader(preparation, quality, AS_PREPARED.textureFix(),
						tuning),
				preparation, 0, AS_PREPARED, picture, true));
	}

	// GL thread, when the host asks after a tuned value changed: writes
	// it into the build that draws, so the next frame shows it. Nothing
	// draws per frame for it.
	public void applyTuning() {
		var build = shaders.current();
		if (build != null) {
			build.applyTuning();
		}
	}

	// Any thread. The runtime of the plan set with this owner while it is
	// shown, or -1.
	public long getRuntimeNanos(@NonNull Object owner) {
		return inputs.getRuntimeNanos(owner);
	}

	// Any thread. Builds the shader requested last again with this
	// quality, or with it once it is prepared.
	public void setQuality(float quality) {
		shaders.offerChanged(offer -> new Offer(
				offer.shader().withQuality(quality),
				offer.origin(),
				offer.runtimeNanos(),
				offer.reporting(),
				offer.picture(),
				offer.continues()));
	}

	// Any thread. The brightest a pixel of the picture may be on the
	// surface, of 255 (PictureLimit), reached at once or in the frames
	// drawn over PictureLimit.FADE_NANOS. Thumbnails are never limited.
	public void limitPicture(int ceiling, boolean fade) {
		limit = new Limit(ceiling / 255f, fade);
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
		device.disable(GLES20.GL_CULL_FACE);
		device.disable(GLES20.GL_BLEND);
		device.disable(GLES20.GL_DEPTH_TEST);
		device.setClearColor(0f, 0f, 0f, 1f);

		discardContextResources();
		device.createContextResources(version);
		formats = device.targetFormats(version);
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
		boolean renderSizeChanged = inputs.resize(width, height);
		sources.setDeviceRotation(getDeviceRotation(context));
		sources.setRenderSize(inputs.getRenderWidth(), inputs.getRenderHeight());
		var camera = sources.get(CameraSource.KEY);
		if (camera != null) {
			camera.reopen();
		}
		// Time and frame number start again with the histories.
		if (renderSizeChanged) {
			thumbnails.onFramesLost();
			if (createCurrentTargets()) {
				inputs.restart(now);
			}
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
				!build.hasTargets()) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			// No picture runs, as after a build that failed: the rate
			// of the one before is not this one's.
			var listener = this.listener;
			if (lastFps != 0 && listener != null) {
				resetFps();
				listener.onFramesPerSecond(0);
			}
			return;
		}

		long now = System.nanoTime();
		var frame = inputs.capture(now, fTimeMax);
		// Once for every program the frame draws.
		sources.update(now);
		renderPipeline.setLimit(limitAt(now));
		build.draw(renderPipeline, builtins, frame);
		// A source that failed after it started, such as a camera that
		// did not open.
		if (sources.takeWarned()) {
			report(revision, sources.withWarnings(built));
		}
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

	// After the host's view was detached. Like onContextLost(), and a plan
	// still being prepared is dropped once it is set; thumbnail requests
	// are answered with null, now and until onAttached().
	public void onDetached() {
		discardContextResources();
		shaders.cancel();
		answerThumbnails(thumbnails.close(), null);
	}

	// When the host's view is attached to its window.
	public void onAttached() {
		thumbnails.open();
	}

	public void pointer(@NonNull MotionEvent e) {
		inputs.post(e);
	}

	public void offset(float x, float y) {
		inputs.postOffset(x, y);
	}

	// Any thread. Answered after a complete frame of the shader requested
	// last, or with null once that shader will not draw.
	public void requestThumbnail(@NonNull ThumbnailCallback callback) {
		if (!thumbnails.add(shaders.requested(), callback)) {
			callback.onThumbnail(null);
			return;
		}
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
		// A next build that a newer request superseded is gone.
		var next = shaders.next();
		if (next == null) {
			return;
		}
		var errors = new ArrayList<ProjectDiagnostic>();
		next.upload(errors);
		// A build of a tuning session goes on from the one that draws.
		var drawing = shaders.current();
		boolean continues = drawing != null &&
				Objects.requireNonNull(shaders.nextRevision()).shader()
						.continues() &&
				shaders.nextIsSamePicture() &&
				next.quality() == drawing.quality();
		// Its targets come before the current build goes, so a build
		// that cannot draw leaves the current one drawing.
		var targetErrors = new ArrayList<ProjectDiagnostic>();
		boolean ready = !next.hasFailed() && next.isComplete() &&
				shaders.makeNextTargets(new ShaderSwap.Targets<>() {
					@NonNull
					@Override
					public ShaderSwap.Allocation make(@NonNull PlanRuntime build) {
						targetErrors.clear();
						return build.createTargets(
								EngineInputs.renderSize(inputs.getSurfaceWidth(),
										build.quality()),
								EngineInputs.renderSize(inputs.getSurfaceHeight(),
										build.quality()),
								continues ? drawing : null,
								targetErrors);
					}

					@Override
					public boolean release(@NonNull PlanRuntime build) {
						return build.releaseTargets();
					}
				});
		errors.addAll(targetErrors);
		if (!ready) {
			if (next.hasFailed() || next.isComplete()) {
				var drawn = shaders.current();
				report(shaders.nextFailed(), errors);
				if (drawn != null && shaders.current() == null) {
					// A failed build of another picture ended it.
					clearConfiguration();
				}
			}
			endCurrentWithoutTargets();
			return;
		}
		// Unless a newer request superseded it while it uploaded.
		if (shaders.swapIfComplete() == null) {
			endCurrentWithoutTargets();
			return;
		}
		thumbnails.onFramesLost();
		var shown = Objects.requireNonNull(shaders.currentRevision());
		var tuning = shown.shader().shader().tuning();
		if (tuning != null) {
			// The value set while it was built.
			next.applyTuning();
			tuning.live();
		}
		long now = System.nanoTime();
		lastRender = now;
		if (!continues) {
			inputs.showRuntime(
					shown.shader().origin(),
					shown.shader().runtimeNanos());
			inputs.restart(next.quality(), now);
		}
		sources.setRenderSize(inputs.getRenderWidth(), inputs.getRenderHeight());
		fTimeMax = next.fTimeMax();
		runSources(next);
		built = errors;
		sources.takeWarned();
		resetFps();
		report(shown, sources.withWarnings(built));
	}

	// After a resize, the build drawing starts again with cleared targets
	// unless none of them changes its size. Returns false when they keep
	// what they hold.
	private boolean createCurrentTargets() {
		var build = shaders.current();
		if (build == null) {
			return true;
		}
		var errors = new ArrayList<ProjectDiagnostic>();
		var allocation = build.createTargets(
				inputs.getRenderWidth(),
				inputs.getRenderHeight(),
				errors);
		if (allocation == ShaderSwap.Allocation.KEPT) {
			return false;
		} else if (allocation != ShaderSwap.Allocation.MADE) {
			report(shaders.currentRevision(), errors);
			endCurrentWithoutTargets();
		}
		return true;
	}

	// The error state: a current build without targets can no longer
	// draw, so it goes with its sources, and its thumbnail requests are
	// answered with null.
	private void endCurrentWithoutTargets() {
		var build = shaders.current();
		if (build != null && !build.hasTargets()) {
			shaders.currentFailed();
			clearConfiguration();
		}
	}

	private void build(@NonNull ShaderSwap.Revision<Offer> revision) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var build = PlanRuntime.link(device, revision.shader().shader(),
				formats, errors);
		if (build == null) {
			if (shaders.failed(revision)) {
				clearConfiguration();
			}
			report(revision, errors);
			return;
		}
		shaders.built(revision, build);
		build.loadTextures(renderRequester);
	}

	// Only the outcome of the newest revision reported so far. A build
	// with a tuned number tells its tuning instead, which decides.
	private void report(
			@Nullable ShaderSwap.Revision<Offer> revision,
			@NonNull List<ProjectDiagnostic> errors) {
		var tuning = revision != null
				? revision.shader().shader().tuning()
				: null;
		if (tuning != null && !Objects.equals(revision,
				shaders.currentRevision())) {
			tuning.refused(errors);
			return;
		}
		if (revision != null && shaders.report(revision)) {
			submitErrors(revision.shader().reporting().locate(errors),
					shaders.showsEarlier(revision));
		}
	}

	private float limitAt(long now) {
		var limit = this.limit;
		if (limit != limitShown) {
			limitShown = limit;
			limitFrom = limitCeiling;
			limitSince = now;
		}
		float t = limit.fade()
				? Math.min(1f, (now - limitSince) /
						(float) PictureLimit.FADE_NANOS)
				: 1f;
		limitCeiling = limitFrom + (limit.ceiling() - limitFrom) * t;
		return PictureLimit.strength(limitCeiling);
	}

	private void resetFps() {
		sum = 0;
		samples = 0;
		lastFps = 0;
		nextFpsUpdate = 0;
	}

	private void submitErrors(@NonNull List<ProjectDiagnostic> errors) {
		submitErrors(errors, false);
	}

	private void submitErrors(@NonNull List<ProjectDiagnostic> errors,
			boolean lastPicture) {
		var listener = this.listener;
		if (listener != null) {
			listener.onLastPicture(lastPicture);
			listener.onDiagnostics(errors);
		}
	}

	private void clearConfiguration() {
		inputs.pause();
		fTimeMax = DEFAULT_FTIME_MAX;
		runSources(null);
	}

	// The one place that decides which sources run: the ones the current
	// shader reads, and none without one. A lost context, a detached
	// view and a host in the background all end the current shader.
	private void runSources(@Nullable PlanRuntime build) {
		if (build == null) {
			activeBuiltins = null;
			sources.stop();
			sources.setCameraBinding(null);
			return;
		}
		sources.setCameraBinding(build.getCameraBinding());
		activeBuiltins = new ActiveBuiltins(device, build.passes(), sources);
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
			var listener = this.listener;
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
