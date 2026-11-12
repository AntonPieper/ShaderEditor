package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

import de.markusfisch.android.shadereditor.opengl.ShaderEngine;
import de.markusfisch.android.shadereditor.project.ShaderProjectSession;

public class ShaderView extends GLSurfaceView {
	private ShaderEngine engine;
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
	protected void onDetachedFromWindow() {
		super.onDetachedFromWindow();
		engine.onContextLost();
	}

	// Click handling is implemented in the engine.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		engine.pointer(event);
		return true;
	}

	public void setShader(@NonNull ShaderProjectSession session) {
		engine.setShader(session);
		requestRender();
	}

	public void setQuality(float quality) {
		engine.setQuality(quality);
		requestRender();
	}

	@NonNull
	public ShaderEngine getEngine() {
		return engine;
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
