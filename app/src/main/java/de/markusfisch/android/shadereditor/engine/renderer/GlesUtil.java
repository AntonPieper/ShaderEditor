package de.markusfisch.android.shadereditor.engine.renderer;

import android.opengl.GLES32;
import android.opengl.GLU;
import android.util.Log;

import androidx.annotation.NonNull;

public class GlesUtil {
	public final static String TAG = "GlesUtil";

	public static void checkErrors(@NonNull String tag) {
		int error;
		boolean hasError = false;
		while ((error = GLES32.glGetError()) != GLES32.GL_NO_ERROR) {
			hasError = true;
			var strError = GLU.gluErrorString(error);
			Log.e(TAG, "OpenGL Error (" + tag + "): " + strError + "(" + error + ")");
		}
		if (hasError) {
			throw new RuntimeException("OpenGL Error");
		}
	}
}
