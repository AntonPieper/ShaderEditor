package de.markusfisch.android.shadereditor.opengl;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.view.Display;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import de.markusfisch.android.shadereditor.database.Database;
import de.markusfisch.android.shadereditor.project.ProjectDiagnostic;

public class ShaderRenderer implements GLSurfaceView.Renderer {
	public interface OnRendererListener {
		void onInfoLog(@NonNull List<ProjectDiagnostic> error);

		void onFramesPerSecond(int fps);
	}

	private static final long FPS_UPDATE_FREQUENCY_NS = 200000000L;
	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_REFRESH_RATE = 60f;
	private static final float DEFAULT_FTIME_MAX = 3f;

	private final GlDevice device = new GlDevice();
	private final RendererProgramManager programManager =
			new RendererProgramManager();
	private final ShaderRenderPipeline renderPipeline =
			new ShaderRenderPipeline(device);
	private final EngineInputs inputs = new EngineInputs();
	private final TextureHandOver<DecodedTextures> textureLoads =
			new TextureHandOver<>();
	private final UniformSources sources;
	private final Context context;
	private final Object thumbnailLock = new Object();

	@Nullable
	private OnRendererListener onRendererListener;
	@Nullable
	private volatile Runnable renderRequester;
	@Nullable
	private ActiveBuiltins activeBuiltins;
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int backBufferUnit = GlUniform.NO_UNIT;
	// Whether the textures must be decoded again, and whether the ones
	// of the last load are uploaded.
	private boolean texturesStale;
	private boolean texturesReady;
	private float fTimeMax = DEFAULT_FTIME_MAX;
	private long lastRender;
	private byte[] thumbnail = new byte[1];
	private boolean captureThumbnail;
	private volatile long nextFpsUpdate;
	private volatile float sum;
	private volatile float samples;
	private volatile int lastFps;
	private volatile float refreshRate = DEFAULT_REFRESH_RATE;

	public ShaderRenderer(@NonNull EngineHost host) {
		context = host.context();
		sources = new UniformSources(host);
	}

	public void setVersion(int version) {
		programManager.setVersion(version);
	}

	public void setFragmentShader(String source, float quality) {
		inputs.setRuntimeNanos(0);
		setQuality(quality);
		programManager.setFragmentShader(source);
		resetFps();
	}

	public long getRuntimeNanos() {
		return inputs.getRuntimeNanos();
	}

	public void setRuntimeNanos(long nanos) {
		inputs.setRuntimeNanos(nanos);
	}

	public void setQuality(float quality) {
		inputs.setQuality(quality);
	}

	public void setOnRendererListener(OnRendererListener listener) {
		onRendererListener = listener;
	}

	// Called when decoded textures are ready, so a view that renders only
	// when dirty draws the frame that waited for them.
	public void setRenderRequester(@Nullable Runnable requester) {
		renderRequester = requester;
	}

	@Override
	public void onSurfaceCreated(GL10 gl, EGLConfig config) {
		device.disable(GLES20.GL_CULL_FACE);
		device.disable(GLES20.GL_BLEND);
		device.disable(GLES20.GL_DEPTH_TEST);
		device.setClearColor(0f, 0f, 0f, 1f);

		discardContextResources();
		device.createContextResources();
		var thumbnailErrors = renderPipeline.createContextResources();
		if (!thumbnailErrors.isEmpty()) {
			submitErrors(thumbnailErrors);
		}

		if (programManager.hasPreparedShader()) {
			resetFps();
			var reloadResult = programManager.reload(device);
			if (reloadResult.succeeded()) {
				submitErrors(Collections.emptyList());
				configure(programManager.getMainProgram());
				texturesStale = true;
			} else {
				submitErrors(reloadResult.programErrors());
			}
		}
	}

	@Override
	public void onSurfaceChanged(GL10 gl, int width, int height) {
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
			// The backbuffer preset is tiled to the render size.
			texturesStale |= programManager.getBackBufferParameters().hasPreset();
		}

		resetFps();
	}

	@Override
	public void onDrawFrame(GL10 gl) {
		var surfaceProgram = programManager.getSurfaceProgram();
		var mainProgram = programManager.getMainProgram();
		if (surfaceProgram == null || mainProgram == null) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			return;
		}

		if (texturesStale) {
			startTextureLoad();
		}
		var textures = textureLoads.take();
		if (textures != null) {
			uploadTextures(textures);
		}
		if (!texturesReady) {
			// A requested thumbnail waits for the first complete frame.
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			return;
		}

		if (!renderPipeline.hasTargets()) {
			var targetErrors = renderPipeline.ensureTargets(
					inputs.getRenderWidth(),
					inputs.getRenderHeight(),
					programManager.getBackBufferSampling(),
					null);
			if (!targetErrors.isEmpty()) {
				submitErrors(targetErrors);
			}
			if (!renderPipeline.hasTargets()) {
				cancelCaptureThumbnail();
				return;
			}
		}

		var builtins = activeBuiltins;
		if (builtins == null) {
			device.clear(GLES20.GL_COLOR_BUFFER_BIT |
					GLES20.GL_DEPTH_BUFFER_BIT);
			cancelCaptureThumbnail();
			return;
		}

		long now = System.nanoTime();
		var frame = inputs.capture(now, fTimeMax);
		builtins.write(frame, now);
		device.bindEmptyTextures(mainProgram);
		device.bindTexture(backBufferUnit, renderPipeline.getBackTexture());
		textureResources.bind(device);

		renderPipeline.renderMainPass(mainProgram);
		renderPipeline.renderSurfacePass(
				surfaceProgram,
				frame.surfaceWidth,
				frame.surfaceHeight);
		captureThumbnail();
		renderPipeline.swapTargets();

		if (onRendererListener != null) {
			updateFps(now);
		}
	}

	public void unregisterListeners() {
		textureLoads.cancel();
		releaseSources();
		clearConfiguration();
	}

	public void touchAt(MotionEvent e) {
		inputs.post(e);
	}

	public void setOffset(float x, float y) {
		inputs.postOffset(x, y);
	}

	public byte[] getThumbnail() {
		synchronized (thumbnailLock) {
			captureThumbnail = true;
			try {
				thumbnailLock.wait(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				captureThumbnail = false;
				return null;
			}
			return thumbnail;
		}
	}

	private void resetFps() {
		sum = 0;
		samples = 0;
		lastFps = 0;
		nextFpsUpdate = 0;
	}

	private void submitErrors(@NonNull List<ProjectDiagnostic> errors) {
		if (onRendererListener != null) {
			onRendererListener.onInfoLog(errors);
		}
	}

	private void configure(@NonNull GlProgram program) {
		releaseSources();
		fTimeMax = programManager.getFTimeMax();
		textureResources = programManager.getTextureResources();
		backBufferUnit = program.getTextureUnit(UniformTable.BACKBUFFER);
		textureResources.resolveUnits(program);
		sources.setCameraBinding(textureResources.getFirstBinding(
				UniformTable.CAMERA_BACK,
				UniformTable.CAMERA_FRONT));
		activeBuiltins = new ActiveBuiltins(device, program, sources);
	}

	// Decodes the textures off the GL thread; until they are uploaded,
	// frames are not drawn.
	private void startTextureLoad() {
		texturesStale = false;
		int generation = textureLoads.begin();
		var samplers = textureResources.getSamplers();
		var backBuffer = programManager.getBackBufferParameters();
		texturesReady = !DecodedTextures.isNeeded(samplers, backBuffer);
		if (texturesReady) {
			return;
		}
		int width = inputs.getRenderWidth();
		int height = inputs.getRenderHeight();
		new Thread(() -> {
			var textureDao = Database.getInstance(context).getDataSource().texture;
			textureLoads.deliver(generation, DecodedTextures.decode(
					samplers,
					backBuffer,
					width,
					height,
					textureDao::getTextureBitmap));
			var requester = renderRequester;
			if (requester != null) {
				requester.run();
			}
		}, "ShaderTextureDecoder").start();
	}

	private void uploadTextures(@NonNull DecodedTextures textures) {
		var errors = new ArrayList<>(textureResources.upload(device, textures));
		renderPipeline.releaseTargets();
		errors.addAll(renderPipeline.ensureTargets(
				inputs.getRenderWidth(),
				inputs.getRenderHeight(),
				programManager.getBackBufferSampling(),
				textures.backBuffer()));
		textures.recycle();
		texturesReady = true;
		if (!errors.isEmpty()) {
			submitErrors(errors);
		}
	}

	private void clearConfiguration() {
		inputs.pauseRuntime();
		fTimeMax = DEFAULT_FTIME_MAX;
		textureResources = ShaderTextureResources.empty();
		backBufferUnit = GlUniform.NO_UNIT;
		activeBuiltins = null;
	}

	private void releaseSources() {
		sources.stop();
		sources.setCameraBinding(null);
	}

	private void discardContextResources() {
		clearConfiguration();
		renderPipeline.discardContextResources();
		programManager.discardContextResources();
	}

	private void captureThumbnail() {
		synchronized (thumbnailLock) {
			var surfaceProgram = programManager.getSurfaceProgram();
			if (captureThumbnail && surfaceProgram != null) {
				thumbnail = renderPipeline.captureThumbnail(surfaceProgram);
				captureThumbnail = false;
				thumbnailLock.notifyAll();
			}
		}
	}

	private void cancelCaptureThumbnail() {
		synchronized (thumbnailLock) {
			if (captureThumbnail) {
				captureThumbnail = false;
				thumbnailLock.notifyAll();
			}
		}
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
			if (fps != lastFps && onRendererListener != null) {
				onRendererListener.onFramesPerSecond(fps);
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
