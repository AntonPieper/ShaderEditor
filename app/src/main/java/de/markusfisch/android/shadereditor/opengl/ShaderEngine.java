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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.markusfisch.android.shadereditor.project.Preparation;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;
import de.markusfisch.android.shadereditor.project.ProjectPath;

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

		// Called on the GL thread, as every call of this listener: when
		// a build begins to draw, with its passes, or with none when a
		// failed build ends what drew. When the host pauses or detaches
		// the view, which ends the GL thread first, passes() is empty
		// from then on without a call; the build that draws after it
		// calls this again.
		default void onPasses(@NonNull RunningPasses passes) {
		}
	}

	// What a host that shows the passes hears while it watches them.
	public interface PassWatcher {
		// Called on the GL thread about four times a second, and after
		// every frame drawn while paused: a picture of every row of
		// passes() that draws, in their order, as the screen would show
		// that pass. The list, its pictures and their pixels are filled
		// again by the next call: copy what is kept beyond this one.
		// Once watchPasses() replaced this watcher, no call starts; one
		// under way ends first, so a call must not wait for the thread
		// that calls watchPasses().
		void onPictures(@NonNull List<PassPicture> pictures);

		// Called on the GL thread right before onPictures(): the number
		// of the frame the pictures are of, the frame drawn last. A pause
		// and every frame drawn while paused tell it at once, so while
		// paused it is the frame shown, as the table in Playback has it:
		// F + 1 after Step, F after a frame drawn again, 0 after Restart
		// or a new build.
		default void onFrame(int frame) {
		}

		// Called on the GL thread right after onPictures(), at most once
		// a second, and after every frame drawn while paused: facts of
		// every row of passes(), in their order.
		default void onFacts(@NonNull List<PassFacts> facts) {
		}
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

	private static final float DEFAULT_REFRESH_RATE = 60f;
	private static final float DEFAULT_FTIME_MAX = 3f;
	// About four pictures of the passes a second.
	private static final long PICTURE_INTERVAL_NS = 250000000L;
	// Facts at most once a second.
	private static final long FACT_INTERVAL_NS = 1000000000L;

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
	@NonNull
	private volatile RunningPasses passes = RunningPasses.NONE;
	// The file of the pass shown instead of the output, or null.
	@Nullable
	private volatile ProjectPath shown;
	private volatile boolean paused;
	@Nullable
	private volatile Fit fit;
	// Who watches the passes, and who was told last; GL thread only:
	// what makes their pictures, and when it did.
	private final PassWatching watching = new PassWatching();
	@Nullable
	private PassWatcher told;
	@Nullable
	private PassInspector inspector;
	private long lastPictures;
	private long lastFacts;
	private final AtomicInteger steps = new AtomicInteger();
	private final AtomicBoolean restartAsked = new AtomicBoolean();
	private final AtomicBoolean againAsked = new AtomicBoolean();
	private final AtomicBoolean paceChanged = new AtomicBoolean();
	// What formats says of rgba16f targets, for any thread.
	private volatile boolean halfFloat;
	private float fTimeMax = DEFAULT_FTIME_MAX;
	// What it tells of the frame rate; GL thread only, as is whether the
	// frame drawn last was paused.
	private final FrameRateReport rate = new FrameRateReport();
	private boolean wasPaused;
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
			// Paused, the frame drawn last shows the value.
			againAsked.set(paused);
		}
	}

	// Any thread. Shows the pass of file full size instead of the output
	// from the next frame on, or the output again for null: one drawn
	// every frame, or a first frame that was drawn, from a build of any
	// revision that has it; else the output. Nothing that runs changes,
	// and thumbnails stay of the output.
	public void showPass(@Nullable ProjectPath file) {
		shown = file;
		requestRender();
	}

	// Any thread. Pauses the picture at the frame drawn last, or lets it
	// run on from there: see Playback. The host draws only on demand
	// while paused.
	public void setPaused(boolean paused) {
		this.paused = paused;
		if (!paused) {
			steps.set(0);
		}
		requestRender();
	}

	// Any thread. While paused, draws the next frame, every pass
	// advancing together, and moves the clock by one frame's time.
	public void step() {
		if (paused) {
			steps.incrementAndGet();
			requestRender();
		}
	}

	// Any thread. The host changed how often it asks for frames, as a
	// frame rate limit does: the rate is measured again from the next
	// frame, as after a new build, and told even when it is the same.
	public void paceChanged() {
		paceChanged.set(true);
	}

	// Any thread. Clears the histories and the clock: the first frames
	// are drawn again and time and frame start at 0, without a build.
	public void restart() {
		restartAsked.set(true);
		requestRender();
	}

	// Any thread. Shows the pass of fit mapped as it says, when it is the
	// one on the screen, and maps its small picture the same way; null
	// for none. Thumbnails are never fitted.
	public void setFit(@Nullable Fit fit) {
		this.fit = fit;
		requestRender();
	}

	// Any thread. While watcher is not null, it hears small pictures of
	// the passes; with null nothing of it costs anything: what made the
	// pictures goes with the next frame.
	public void watchPasses(@Nullable PassWatcher watcher) {
		watching.set(watcher);
		requestRender();
	}

	// Any thread. The passes of the build that draws, or none.
	@NonNull
	public RunningPasses passes() {
		return passes;
	}

	// Any thread. The runtime of the plan set with this owner while it is
	// shown, or -1.
	public long getRuntimeNanos(@NonNull Object owner) {
		return inputs.getRuntimeNanos(owner);
	}

	// Any thread. Builds the shader requested last again with this
	// quality, or with it once it is prepared.
	/**
	 * Return whether the current context draws into rgba16f targets;
	 * false before there is one. Any thread.
	 */
	public boolean drawsHalfFloat() {
		return halfFloat;
	}

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
		halfFloat = formats.halfFloatRendering();
		var errors = renderPipeline.createContextResources();
		if (!errors.isEmpty()) {
			submitErrors(errors);
		}
	}

	// GL thread.
	public void onSurfaceChanged(int width, int height) {
		long now = System.nanoTime();
		rate.setRefreshRate(getRefreshRate(context));
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

		rate.start();
	}

	// GL thread.
	public void drawFrame() {
		// Before a newer shader replaces the frame it was asked for.
		answerThumbnails();
		var swap = swapInNewestShader();
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
			if (watching.get() == null && inspector != null) {
				inspector.release();
				inspector = null;
			}
			// No picture runs, as after a build that failed: the rate
			// of the one before is not this one's.
			tellRate(rate.none());
			return;
		}

		long now = System.nanoTime();
		boolean paused = this.paused;
		inputs.setPaused(paused, now);
		boolean pauseChanged = paused != wasPaused;
		wasPaused = paused;
		if (pauseChanged && !paused) {
			// Play: the stopped time is in no sample.
			rate.start();
		}
		if (paceChanged.getAndSet(false)) {
			// A new limit: no sample is of the pace before.
			rate.start();
		}
		if (restartAsked.getAndSet(false)) {
			build.restart();
			inputs.restart(now);
			thumbnails.onFramesLost();
		}
		// The row of the table in Playback, in one place.
		var draw = Playback.decide(paused, steps.get() > 0,
				againAsked.getAndSet(false), inputs.restarted(),
				build.needsFrame(), swap);
		if (draw == Playback.Draw.STEP) {
			takeStep();
		}
		renderPipeline.setLimit(limitAt(now));
		int shownPass = build.shown(shown);
		var fit = this.fit;
		renderPipeline.setFit(fit != null &&
				fit.file().equals(build.fileOf(shownPass)) ? fit : null);
		if (draw == Playback.Draw.PRESENT) {
			build.present(renderPipeline, inputs.getSurfaceWidth(),
					inputs.getSurfaceHeight(), shownPass);
			thumbnails.onFrame(revision.number());
			answerThumbnails();
			tellRate(rate.paused(inputs.frameRate()));
			watch(build, now, pauseChanged);
			return;
		}
		var frame = inputs.capture(draw, now, fTimeMax);
		// Once for every program the frame draws.
		sources.update(now);
		build.draw(renderPipeline, builtins, frame, shownPass,
				draw == Playback.Draw.AGAIN);
		// A source that failed after it started, such as a camera that
		// did not open.
		if (sources.takeWarned()) {
			report(revision, sources.withWarnings(built));
		}
		thumbnails.onFrame(revision.number());
		answerThumbnails();
		watch(build, now, draw != Playback.Draw.FRAME ||
				swap != Playback.Swap.NONE || pauseChanged);

		tellRate(draw == Playback.Draw.FRAME
				? rate.frame(now)
				: rate.paused(inputs.frameRate()));
		// Steps asked for faster than frames come are drawn one a frame.
		if (paused && steps.get() > 0) {
			requestRender();
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

	// Whether a build began to draw, and whether it goes on from the one
	// before.
	@NonNull
	private Playback.Swap swapInNewestShader() {
		for (var revision = shaders.take();
				revision != null;
				revision = shaders.take()) {
			build(revision);
		}
		// A next build that a newer request superseded is gone.
		var next = shaders.next();
		if (next == null) {
			return Playback.Swap.NONE;
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
					endPicture();
				}
			}
			endCurrentWithoutTargets();
			return Playback.Swap.NONE;
		}
		// Unless a newer request superseded it while it uploaded.
		if (shaders.swapIfComplete() == null) {
			endCurrentWithoutTargets();
			return Playback.Swap.NONE;
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
		if (!continues) {
			inputs.showRuntime(
					shown.shader().origin(),
					shown.shader().runtimeNanos());
			inputs.restart(next.quality(), now);
		}
		sources.setRenderSize(inputs.getRenderWidth(), inputs.getRenderHeight());
		fTimeMax = next.fTimeMax();
		runSources(next);
		showPasses(next.running());
		built = errors;
		sources.takeWarned();
		rate.start();
		report(shown, sources.withWarnings(built));
		return continues ? Playback.Swap.CONTINUED : Playback.Swap.NEW;
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
			endPicture();
		}
	}

	private void build(@NonNull ShaderSwap.Revision<Offer> revision) {
		var errors = new ArrayList<ProjectDiagnostic>();
		var build = PlanRuntime.link(device, revision.shader().shader(),
				formats, errors);
		if (build == null) {
			if (shaders.failed(revision)) {
				endPicture();
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

	// GL thread. Nothing draws any more: as clearConfiguration(), and the
	// host hears that no passes draw.
	private void endPicture() {
		clearConfiguration();
		showPasses(RunningPasses.NONE);
	}

	// GL thread. Tells the watcher what the passes drew in the frame
	// drawn last: four times a second, or now when the frame is one it
	// has not seen, such as one drawn while paused; and lets the
	// pictures go when no one watches.
	private void watch(@NonNull PlanRuntime build, long now,
			boolean fresh) {
		var watcher = watching.get();
		if (watcher == null) {
			if (inspector != null) {
				inspector.release();
				inspector = null;
			}
			told = null;
			return;
		}
		if (!fresh && watcher == told &&
				now - lastPictures < PICTURE_INTERVAL_NS) {
			return;
		}
		boolean first = watcher != told;
		told = watcher;
		lastPictures = now;
		if (inspector == null) {
			inspector = new PassInspector(device);
		}
		build.lastFrames(inspector.sources());
		var pictures = inspector.pictures(fit);
		// Not to one that stopped watching meanwhile, nor after.
		if (!watching.frame(watcher, inputs.lastFrame()) ||
				!watching.pictures(watcher, pictures)) {
			return;
		}
		if (fresh || first || now - lastFacts >= FACT_INTERVAL_NS) {
			lastFacts = now;
			watching.facts(watcher, inspector.facts(build.running(), now,
					formats.glesVersion() >= 3));
		}
	}

	// A step asked for, taken.
	private boolean takeStep() {
		for (int n; (n = steps.get()) > 0; ) {
			if (steps.compareAndSet(n, n - 1)) {
				return true;
			}
		}
		return false;
	}

	private void requestRender() {
		var requester = renderRequester;
		if (requester != null) {
			requester.run();
		}
	}

	// GL thread. What draws now, for a host that shows the passes.
	private void showPasses(@NonNull RunningPasses running) {
		if (passes.equals(running)) {
			return;
		}
		passes = running;
		var listener = this.listener;
		if (listener != null) {
			listener.onPasses(running);
		}
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

	// After the GL thread ended, on the host's thread: passes() is empty
	// without a call, as the listener is called on the GL thread only.
	private void discardContextResources() {
		clearConfiguration();
		passes = RunningPasses.NONE;
		shaders.contextLost();
		renderPipeline.discardContextResources();
		thumbnails.onFramesLost();
		if (inspector != null) {
			inspector.discard();
			inspector = null;
		}
		told = null;
	}

	// Tells the listener a rate FrameRateReport said, if any.
	private void tellRate(int fps) {
		var listener = this.listener;
		if (fps != FrameRateReport.NOTHING && listener != null) {
			listener.onFramesPerSecond(fps);
		}
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
