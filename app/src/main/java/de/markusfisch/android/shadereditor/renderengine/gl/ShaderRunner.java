package de.markusfisch.android.shadereditor.renderengine.gl;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Defines the contract for an object that can run a rendering lifecycle.
 * The GLSurfaceView.Renderer will delegate its calls to an implementation
 * of this interface.
 */
public interface ShaderRunner {
	void onSurfaceCreated(GL10 gl, EGLConfig config);

	void onSurfaceChanged(GL10 gl, int width, int height);

	void onDrawFrame(GL10 gl);

	void onSurfaceDestroyed();
}