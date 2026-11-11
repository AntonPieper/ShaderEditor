package de.markusfisch.android.shadereditor.opengl;

import android.content.Context;
import android.view.MotionEvent;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class BuiltinUniforms {
	record SurfaceState(int renderWidth, int renderHeight, boolean renderTargetsChanged) {
		@NonNull
		static SurfaceState empty() {
			return new SurfaceState(0, 0, false);
		}
	}

	record PreparedFrame(
			int surfaceWidth,
			int surfaceHeight,
			long now) {
	}

	private static final float NS_PER_SECOND = 1000000000f;
	private static final float DEFAULT_FTIME_MAX = 3f;

	private final float[] surfaceResolution = new float[]{0, 0};
	private final float[] resolution = new float[]{0, 0};
	private final float[] touch = new float[]{0, 0};
	private final float[] touchStart = new float[]{0, 0};
	private final float[] mouse = new float[]{0, 0};
	private final float[] pointers = new float[FrameInputs.MAX_POINTERS * 3];
	private final float[] offset = new float[]{0, 0};
	private final FrameInputs frameInputs = new FrameInputs();
	@NonNull
	private final Context context;
	@NonNull
	private final UniformSources sources;

	@Nullable
	private GlDevice device;
	@Nullable
	private GlProgram program;
	@Nullable
	private ActiveBuiltins activeBuiltins;
	@NonNull
	private ShaderTextureResources textureResources =
			ShaderTextureResources.empty();
	private int backBufferUnit = GlUniform.NO_UNIT;
	private int pointerCount;
	private int frameNum;
	private long startTime;
	private float fTimeMax = DEFAULT_FTIME_MAX;
	private float quality = 1f;
	private float startRandom;

	BuiltinUniforms(@NonNull EngineHost host) {
		context = host.context();
		sources = new UniformSources(host);
	}

	void setQuality(float quality) {
		this.quality = quality;
	}

	void configure(
			@NonNull GlDevice device,
			@NonNull GlProgram program,
			float fTimeMax,
			@NonNull ShaderTextureResources textureResources) {
		releaseModules();
		this.fTimeMax = fTimeMax;
		this.textureResources = textureResources;
		this.device = device;
		this.program = program;
		backBufferUnit = program.getTextureUnit(UniformTable.BACKBUFFER);
		textureResources.resolveUnits(program);

		sources.setCameraBinding(textureResources.getFirstBinding(
				UniformTable.CAMERA_BACK,
				UniformTable.CAMERA_FRONT));
		activeBuiltins = new ActiveBuiltins(device, program, sources);
	}

	@NonNull
	SurfaceState updateSurface(int width, int height, long now) {
		startTime = now;
		startRandom = (float) Math.random();
		frameNum = 0;

		surfaceResolution[0] = width;
		surfaceResolution[1] = height;
		int deviceRotation = getDeviceRotation(context);
		sources.setDeviceRotation(deviceRotation);

		float w = Math.round(width * quality);
		float h = Math.round(height * quality);
		boolean resolutionChanged = w != resolution[0] || h != resolution[1];
		resolution[0] = w;
		resolution[1] = h;

		sources.setRenderSize((int) resolution[0], (int) resolution[1]);
		var camera = sources.get(CameraSource.KEY);
		if (camera != null) {
			camera.reopen();
		}
		return new SurfaceState(
				(int) resolution[0],
				(int) resolution[1],
				resolutionChanged);
	}

	void updateTouch(@NonNull MotionEvent e) {
		float x = e.getX() * quality;
		float y = e.getY() * quality;

		touch[0] = x;
		touch[1] = resolution[1] - y;

		mouse[0] = x / resolution[0];
		mouse[1] = 1 - y / resolution[1];

		switch (e.getActionMasked()) {
			case MotionEvent.ACTION_DOWN:
				touchStart[0] = touch[0];
				touchStart[1] = touch[1];
				break;
			case MotionEvent.ACTION_UP:
			case MotionEvent.ACTION_CANCEL:
				pointerCount = 0;
				return;
			default:
				break;
		}

		pointerCount = Math.min(e.getPointerCount(), pointers.length / 3);
		for (int i = 0, pointerOffset = 0; i < pointerCount; ++i) {
			pointers[pointerOffset++] = e.getX(i) * quality;
			pointers[pointerOffset++] = resolution[1] - e.getY(i) * quality;
			pointers[pointerOffset++] = e.getTouchMajor(i);
		}
	}

	void updateOffset(float x, float y) {
		offset[0] = x;
		offset[1] = y;
	}

	@Nullable
	PreparedFrame beginFrame(@Nullable GlTexture2D backBufferTexture) {
		var device = this.device;
		var program = this.program;
		var builtins = activeBuiltins;
		if (device == null || program == null || builtins == null) {
			return null;
		}

		long now = System.nanoTime();
		float delta = (now - startTime) / NS_PER_SECOND;

		captureFrameInputs(delta);
		builtins.write(frameInputs, now);
		device.bindEmptyTextures(program);
		device.bindTexture(backBufferUnit, backBufferTexture);
		textureResources.bind(device);

		return new PreparedFrame(
				(int) surfaceResolution[0],
				(int) surfaceResolution[1],
				now);
	}

	void endFrame() {
		++frameNum;
	}

	void clearConfiguration() {
		fTimeMax = DEFAULT_FTIME_MAX;
		textureResources = ShaderTextureResources.empty();
		device = null;
		program = null;
		backBufferUnit = GlUniform.NO_UNIT;
		activeBuiltins = null;
	}

	void release() {
		releaseModules();
		clearConfiguration();
	}

	private void captureFrameInputs(float delta) {
		var in = frameInputs;
		in.time = delta;
		in.frame = frameNum;
		in.fTimeMax = fTimeMax;
		in.width = resolution[0];
		in.height = resolution[1];
		in.touchX = touch[0];
		in.touchY = touch[1];
		in.touchStartX = touchStart[0];
		in.touchStartY = touchStart[1];
		in.mouseX = mouse[0];
		in.mouseY = mouse[1];
		int count = pointerCount;
		in.pointerCount = count;
		System.arraycopy(pointers, 0, in.pointers, 0, count * 3);
		in.offsetX = offset[0];
		in.offsetY = offset[1];
		in.startRandom = startRandom;
	}

	private void releaseModules() {
		sources.stop();
		sources.setCameraBinding(null);
	}

	private static int getDeviceRotation(@NonNull Context context) {
		WindowManager windowManager = (WindowManager) context.getSystemService(
				Context.WINDOW_SERVICE);
		if (windowManager == null) {
			return 0;
		}

		return windowManager.getDefaultDisplay().getRotation();
	}
}
