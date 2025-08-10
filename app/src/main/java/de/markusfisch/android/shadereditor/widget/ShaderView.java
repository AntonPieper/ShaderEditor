package de.markusfisch.android.shadereditor.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import de.markusfisch.android.shadereditor.opengl.ShaderRenderer;

public class ShaderView extends GLSurfaceView {
	private ShaderRenderer renderer;

	public ShaderView(Context context, int renderMode) {
		super(context);
		init(context);
	}

	public ShaderView(Context context) {
		super(context);
		init(context);
	}

	public ShaderView(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	@Override
	public void onPause() {
		super.onPause();
		renderer.unregisterListeners();
	}

	// Click handling is implemented in renderer.
	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent event) {
		renderer.touchAt(event);
		return true;
	}

	public void setFragmentShader(String src, float quality) {
		onPause();
		// When pasting text from other apps, e.g. Gmail, the
		// text is sometimes tainted with useless non-ascii
		// characters that can raise an exception in the shader
		// compiler. To still allow UTF-8 characters in comments,
		// the source is cleaned up here.
		renderer.setFragmentShader(removeNonAscii(src), quality);
		onResume();
	}

	public ShaderRenderer getRenderer() {
		return renderer;
	}

	private void init(Context context) {
		renderer = new ShaderRenderer(context);

		// On some devices it's important to setEGLContextClientVersion()
		// even if the docs say it's not used when setEGLContextFactory()
		// is called. Not doing so will crash the app (e.g. on the FP1).
	}

	private static String removeNonAscii(String text) {
		return text == null
				? null
				: text.replaceAll("[^\\x0A\\x09\\x20-\\x7E]", "");
	}

}
